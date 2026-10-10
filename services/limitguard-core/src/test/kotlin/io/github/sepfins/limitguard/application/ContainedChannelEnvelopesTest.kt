package io.github.sepfins.limitguard.application

import io.github.sepfins.limitguard.api.LimitChannelScope
import io.github.sepfins.limitguard.api.AuthorizationDecision
import io.github.sepfins.limitguard.api.LimitRuleChangeStatus
import io.github.sepfins.limitguard.api.LimitRuleChangeType
import io.github.sepfins.limitguard.api.LimitKind
import io.github.sepfins.limitguard.api.LimitPeriod
import io.github.sepfins.limitguard.api.commands.CreateChannelGroupCommand
import io.github.sepfins.limitguard.api.commands.UpdateLimitConfigurationCommand
import io.github.sepfins.limitguard.api.commands.SetLimitConfigurationActiveCommand
import io.github.sepfins.limitguard.api.errors.ValidationException
import io.github.sepfins.limitguard.api.errors.NotFoundException
import io.github.sepfins.limitguard.domain.common.ChannelId
import io.github.sepfins.limitguard.domain.common.ChannelGroupId
import io.github.sepfins.limitguard.domain.channel.ChannelGroupMember
import io.github.sepfins.limitguard.domain.common.LimitConfigurationId
import io.github.sepfins.limitguard.domain.common.TenantId
import io.github.sepfins.limitguard.domain.limit.LimitChannelScope as DomainChannelScope
import io.github.sepfins.limitguard.support.FixedClockProvider
import io.github.sepfins.limitguard.support.FundingEnvelopeScenario
import io.github.sepfins.limitguard.support.createFacade
import io.github.sepfins.limitguard.support.persistence.InMemoryLimitguardRepositories
import java.time.Instant
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ContainedChannelEnvelopesTest {
    @Test
    fun `all channels cannot fit an empty group envelope even with an empty channel catalog`() {
        val s = scenario()
        val group = s.facade.tenantCatalogService.createChannelGroup(CreateChannelGroupCommand(s.tenant, "EMPTY", "Empty"))
        val parent = s.facade.tenantCatalogService.createLimitConfiguration(s.configurationCommand("PARENT").copy(
            channelScope = LimitChannelScope.CHANNEL_GROUP, channelGroupId = group.id,
        ))

        assertFailsWith<ValidationException> { s.configuration("ALL", parent.id) }

        assertNull(s.findConfiguration("ALL"))
    }

    @Test
    fun `all single and group selectors compare live channel coverage in both directions`() {
        val s = scenario()
        val api = s.registerChannel("API")
        val web = s.registerChannel("WEB", active = false)
        val group = s.channelGroup("DIGITAL", api.id, web.id)
        val selectors = listOf(
            s.configurationCommand("ALL"),
            s.configurationCommand("SINGLE", channelId = api.id),
            s.configurationCommand("GROUP", channelGroupId = group.id),
        )
        val expected = listOf(listOf(true, true, true), listOf(false, true, false), listOf(false, true, true))
        val parents = selectors.map { s.facade.tenantCatalogService.createLimitConfiguration(it) }

        parents.forEachIndexed { row, parent ->
            selectors.forEachIndexed { column, selector ->
                val code = "CHILD_${row}_$column"
                val command = selector.copy(code = code, name = code, parentConfigurationId = parent.id)
                if (expected[row][column]) {
                    assertEquals(parent.id, s.facade.tenantCatalogService.createLimitConfiguration(command).parentConfigurationId)
                } else {
                    assertFailsWith<ValidationException> { s.facade.tenantCatalogService.createLimitConfiguration(command) }
                    assertNull(s.findConfiguration(code))
                }
            }
        }
    }

    @Test
    fun `disabled empty group envelopes reject incompatible growth atomically and permit a corrected retry`() {
        val s = scenario()
        val api = s.registerChannel("API")
        val web = s.registerChannel("WEB", active = false)
        val parentGroup = s.channelGroup("PARENT_GROUP", api.id)
        val childGroup = s.channelGroup("CHILD_GROUP")
        val parent = s.configuration("PARENT", channelGroupId = parentGroup.id, active = false)
        val child = s.configuration("CHILD", parent.id, channelGroupId = childGroup.id, active = false)
        s.addChannelMember(childGroup.id, api.id)

        val failure = assertFailsWith<ValidationException> { s.addChannelMember(childGroup.id, web.id) }

        assertTrue(failure.message.orEmpty().contains(child.id.toString()))
        assertTrue(failure.message.orEmpty().contains(parent.id.toString()))
        assertEquals(parent, s.findConfiguration("PARENT"))
        assertEquals(child, s.findConfiguration("CHILD"))
        s.addChannelMember(parentGroup.id, web.id)
        assertEquals(web.id, s.addChannelMember(childGroup.id, web.id).channelId)
    }

    @Test
    fun `creation and descriptive updates report invalid live coverage beyond the immediate parent without rewriting the hierarchy`() {
        val repositories = InMemoryLimitguardRepositories()
        val s = scenario(repositories)
        val api = s.registerChannel("API")
        val web = s.registerChannel("WEB")
        val group = s.channelGroup("WEB_GROUP", web.id)
        val root = s.configuration("ROOT")
        val parent = s.configuration("PARENT", root.id, channelGroupId = group.id)
        val child = s.configuration("CHILD", parent.id, channelGroupId = group.id, active = false)
        val tenant = TenantId.of(s.tenant)
        val stored = requireNotNull(repositories.limitConfigurations.findById(tenant, LimitConfigurationId.of(root.id)))
        repositories.limitConfigurations.update(tenant, stored.copy(channelScope = DomainChannelScope.ChannelSpecific(ChannelId.of(api.id))))
        val invalidRoot = s.findConfiguration("ROOT")

        assertFailsWith<ValidationException> { s.configuration("NEW", child.id, channelGroupId = group.id) }
        assertNull(s.findConfiguration("NEW"))

        val failure = assertFailsWith<ValidationException> {
            s.facade.tenantCatalogService.updateLimitConfiguration(UpdateLimitConfigurationCommand(
                s.tenant, child.id, "Changed", "Changed", BigDecimal.ONE, mapOf("changed" to true),
            ))
        }

        assertTrue(failure.message.orEmpty().contains(parent.id.toString()))
        assertTrue(failure.message.orEmpty().contains(root.id.toString()))
        assertEquals(invalidRoot, s.findConfiguration("ROOT"))
        assertEquals(child, s.findConfiguration("CHILD"))
    }

    @Test
    fun `activation and no-op deactivation report invalid existing membership without silently correcting it`() {
        val repositories = InMemoryLimitguardRepositories()
        val s = scenario(repositories)
        val api = s.registerChannel("API")
        val web = s.registerChannel("WEB", active = false)
        val group = s.channelGroup("EMPTY")
        val parent = s.configuration("PARENT", channelId = api.id)
        val child = s.configuration("CHILD", parent.id, channelGroupId = group.id, active = false)
        repositories.channelGroupMembers.insert(TenantId.of(s.tenant), ChannelGroupMember(
            TenantId.of(s.tenant), ChannelGroupId.of(group.id), ChannelId.of(web.id), NOW,
        ))

        for (active in listOf(true, false)) {
            val failure = assertFailsWith<ValidationException> {
                s.facade.tenantCatalogService.setLimitConfigurationActive(SetLimitConfigurationActiveCommand(s.tenant, child.id, active))
            }
            assertTrue(failure.message.orEmpty().contains("correct the channel envelope explicitly"))
            assertEquals(child, s.findConfiguration("CHILD"))
        }
    }

    @Test
    fun `distinct groups with equal or subset coverage are contained while different channel coverage is rejected`() {
        val s = scenario()
        val api = s.registerChannel("API")
        val web = s.registerChannel("WEB")
        val branch = s.registerChannel("BRANCH")
        val broad = s.channelGroup("BROAD", api.id, web.id)
        val equal = s.channelGroup("EQUAL", web.id, api.id)
        val subset = s.channelGroup("SUBSET", api.id)
        val outside = s.channelGroup("OUTSIDE", api.id, branch.id)
        val parent = s.configuration("PARENT", channelGroupId = broad.id)

        assertEquals(parent.id, s.configuration("EQUAL", parent.id, channelGroupId = equal.id).parentConfigurationId)
        val narrow = s.configuration("SUBSET", parent.id, channelGroupId = subset.id)
        assertEquals(narrow.id, s.configuration("SINGLE", narrow.id, channelId = api.id).parentConfigurationId)
        assertEquals(api.id, s.configuration("SINGLE_PARENT", channelId = api.id).channelId)
        val singleParent = requireNotNull(s.findConfiguration("SINGLE_PARENT"))
        assertEquals(singleParent.id, s.configuration("SINGLE_GROUP", singleParent.id, channelGroupId = subset.id).parentConfigurationId)
        for (command in listOf(
            s.configurationCommand("INVALID", parent.id, channelGroupId = outside.id),
            s.configurationCommand("INVALID", narrow.id, channelGroupId = broad.id),
            s.configurationCommand("INVALID", parent.id, channelId = branch.id),
            s.configurationCommand("INVALID", singleParent.id, channelId = web.id),
            s.configurationCommand("INVALID", parent.id),
        )) {
            assertFailsWith<ValidationException> { s.facade.tenantCatalogService.createLimitConfiguration(command) }
            assertNull(s.findConfiguration("INVALID"))
        }
    }

    @Test
    fun `empty groups fit single and empty group parents until incompatible growth`() {
        val s = scenario()
        val api = s.registerChannel("API")
        val web = s.registerChannel("WEB")
        val first = s.channelGroup("FIRST")
        val second = s.channelGroup("SECOND")
        val single = s.configuration("SINGLE", channelId = api.id)
        val parent = s.configuration("PARENT", single.id, channelGroupId = first.id)
        val child = s.configuration("CHILD", parent.id, channelGroupId = second.id)
        assertEquals(child.id, s.configuration("SAME_EMPTY", child.id, channelGroupId = second.id).parentConfigurationId)

        assertFailsWith<ValidationException> { s.addChannelMember(second.id, api.id) }
        assertFailsWith<ValidationException> { s.addChannelMember(first.id, web.id) }
        s.addChannelMember(first.id, api.id)
        s.addChannelMember(second.id, api.id)
        assertEquals(child.id, s.configuration("COVERED", child.id, channelId = api.id).parentConfigurationId)
        assertEquals(child, s.findConfiguration("CHILD"))
    }

    @Test
    fun `group growth must satisfy every hierarchy using that group including its ancestor edges`() {
        val s = scenario()
        val api = s.registerChannel("API")
        val web = s.registerChannel("WEB")
        val group = s.channelGroup("SHARED", api.id)
        val all = s.configuration("ALL")
        val single = s.configuration("SINGLE", channelId = api.id, active = false)
        val broadChild = s.configuration("BROAD_CHILD", all.id, channelGroupId = group.id)
        val narrowChild = s.configuration("NARROW_CHILD", single.id, channelGroupId = group.id, active = false)
        val grandchild = s.configuration("GRANDCHILD", narrowChild.id, channelId = api.id, active = false)

        assertFailsWith<ValidationException> { s.addChannelMember(group.id, web.id) }

        assertEquals(broadChild, s.findConfiguration("BROAD_CHILD"))
        assertEquals(narrowChild, s.findConfiguration("NARROW_CHILD"))
        assertEquals(grandchild, s.findConfiguration("GRANDCHILD"))
        assertFailsWith<ValidationException> { s.configuration("OUTSIDE", broadChild.id, channelId = web.id) }
        assertNull(s.findConfiguration("OUTSIDE"))
    }

    @Test
    fun `channel references memberships and envelopes remain tenant scoped`() {
        val s = scenario()
        val other = FundingEnvelopeScenario(s.facade, NOW)
        val api = s.registerChannel("API")
        val local = s.channelGroup("LOCAL")
        val foreignChannel = other.registerChannel("OTHER")
        val foreignGroup = other.channelGroup("OTHER", foreignChannel.id)
        val foreignParent = other.configuration("OTHER", channelGroupId = foreignGroup.id)

        assertFailsWith<NotFoundException> { s.configuration("FOREIGN", channelId = foreignChannel.id) }
        assertFailsWith<NotFoundException> { s.configuration("FOREIGN", channelGroupId = foreignGroup.id) }
        assertFailsWith<NotFoundException> { s.configuration("FOREIGN", foreignParent.id, channelId = api.id) }
        assertFailsWith<NotFoundException> { s.addChannelMember(local.id, foreignChannel.id) }
        assertFailsWith<NotFoundException> { s.addChannelMember(foreignGroup.id, api.id) }
        assertNull(s.findConfiguration("FOREIGN"))
        assertEquals(api.id, s.addChannelMember(local.id, api.id).channelId)
        assertEquals(foreignParent, other.findConfiguration("OTHER"))
    }

    @Test
    fun `channel containment retains amount default currency kind period and time constraints`() {
        val s = scenario()
        val api = s.registerChannel("API")
        val group = s.channelGroup("GROUP", api.id)
        val rootCommand = s.configurationCommand("ROOT", channelGroupId = group.id).copy(endsAt = NOW.plusSeconds(3600))
        val root = s.facade.tenantCatalogService.createLimitConfiguration(rootCommand)
        val child = s.configurationCommand("CHILD", root.id, channelId = api.id).copy(endsAt = root.endsAt)

        for (invalid in listOf(
            child.copy(currency = "USD"),
            child.copy(maxAssignableAmount = BigDecimal("1001")),
            child.copy(maxAssignableAmount = BigDecimal("500"), defaultAmount = BigDecimal("501")),
            child.copy(defaultAmount = BigDecimal.ZERO),
            child.copy(startsAt = NOW.minusSeconds(1)),
            child.copy(endsAt = null),
            child.copy(endsAt = NOW.plusSeconds(3601)),
            child.copy(limitKind = LimitKind.PERIODIC, period = LimitPeriod.DAILY),
        )) {
            assertFailsWith<ValidationException> { s.facade.tenantCatalogService.createLimitConfiguration(invalid) }
            assertNull(s.findConfiguration("CHILD"))
        }
        assertEquals(root.id, s.facade.tenantCatalogService.createLimitConfiguration(child).parentConfigurationId)
        val daily = s.facade.tenantCatalogService.createLimitConfiguration(rootCommand.copy(
            code = "DAILY", limitKind = LimitKind.PERIODIC, period = LimitPeriod.DAILY,
        ))
        assertFailsWith<ValidationException> {
            s.facade.tenantCatalogService.createLimitConfiguration(child.copy(
                code = "MONTHLY", parentConfigurationId = daily.id, limitKind = LimitKind.PERIODIC, period = LimitPeriod.MONTHLY,
            ))
        }
    }

    @Test
    fun `request channel is independent of the selected rules exact channel selector`() {
        val s = scenario()
        val atm = s.registerChannel("ATM")
        val group = s.channelGroup("ATMS", atm.id)
        val equivalent = s.channelGroup("EQUIVALENT", atm.id)
        val parent = s.configuration("PARENT")
        val selected = s.configuration("SELECTED", parent.id, channelGroupId = group.id)
        val command = s.requestCommand(selected)
        val draft = requireNotNull(command.requestedRule)

        for (invalid in listOf(
            draft.copy(channelScope = LimitChannelScope.ALL_CHANNELS, channelGroupId = null),
            draft.copy(channelGroupId = equivalent.id),
            draft.copy(channelScope = LimitChannelScope.CHANNEL, channelGroupId = null, channelId = atm.id),
            draft.copy(priority = selected.priority + 1),
            draft.copy(amount = BigDecimal("1001")),
            draft.copy(startsAt = NOW.minusSeconds(1)),
        )) {
            assertFailsWith<ValidationException> { s.facade.limitChangeService.requestLimitRuleChange(command.copy(requestedRule = invalid)) }
        }
        val request = s.facade.limitChangeService.requestLimitRuleChange(command)
        assertEquals(s.channel, request.requestChannelId)
        val applied = s.approve(request.id)
        assertEquals(LimitRuleChangeStatus.APPLIED, applied.status)
        assertEquals(group.id, s.rule(requireNotNull(applied.limitRuleId)).channelGroupId)
    }

    @Test
    fun `disabled channel configurations block new assignment but preserve granted rules and withdrawal`() {
        for (disabledLevel in 0..2) {
            val s = scenario()
            val atm = s.registerChannel("ATM")
            val branch = s.registerChannel("BRANCH")
            val broad = s.channelGroup("BROAD", atm.id, branch.id)
            val narrow = s.channelGroup("NARROW", atm.id)
            val root = s.configuration("ROOT", channelGroupId = broad.id)
            val parent = s.configuration("PARENT", root.id, channelGroupId = narrow.id)
            val selected = s.configuration("SELECTED", parent.id, channelId = atm.id)
            val source = s.source(s.product("ACCOUNT", leaf = true).id)
            val rule = s.grant(selected)
            val command = s.requestCommand(selected)
            val disabled = listOf(selected, parent, root)[disabledLevel]
            s.facade.tenantCatalogService.setLimitConfigurationActive(SetLimitConfigurationActiveCommand(s.tenant, disabled.id, false))

            assertFailsWith<ValidationException> { s.facade.limitChangeService.requestLimitRuleChange(command) }
            assertFailsWith<ValidationException> {
                s.facade.limitChangeService.requestLimitRuleChange(command.copy(
                    changeType = LimitRuleChangeType.UPDATE, limitRuleId = rule.id, expectedVersion = 1,
                ))
            }
            val allowed = s.authorize(source, "40", atm.id)
            assertEquals(AuthorizationDecision.ALLOWED, allowed.decision)
            assertEquals(listOf(rule.id), allowed.matchedRules.map { it.id })
            assertEquals(AuthorizationDecision.DENIED, s.authorize(source, "501", atm.id).decision)
            assertEquals(rule, s.rule(rule.id))
            val deactivate = s.facade.limitChangeService.requestLimitRuleChange(command.copy(
                changeType = LimitRuleChangeType.DEACTIVATE, requestedRule = null, limitRuleId = rule.id, expectedVersion = 1,
            ))
            assertEquals(LimitRuleChangeStatus.APPLIED, s.approve(deactivate.id).status)
            assertFailsWith<ValidationException> {
                s.facade.limitChangeService.requestLimitRuleChange(command.copy(
                    changeType = LimitRuleChangeType.REACTIVATE, requestedRule = null, limitRuleId = rule.id, expectedVersion = 2,
                ))
            }
            val retire = s.facade.limitChangeService.requestLimitRuleChange(command.copy(
                changeType = LimitRuleChangeType.DELETE, requestedRule = null, limitRuleId = rule.id, expectedVersion = 2,
            ))
            assertEquals(LimitRuleChangeStatus.APPLIED, s.approve(retire.id).status)
            assertEquals(3, s.rule(rule.id).version)
        }
    }

    private fun scenario(repositories: InMemoryLimitguardRepositories = InMemoryLimitguardRepositories()) =
        FundingEnvelopeScenario(repositories.createFacade(FixedClockProvider(NOW), emptyList()), NOW)

    companion object {
        private val NOW = Instant.parse("2026-10-10T10:00:00Z")
    }
}
