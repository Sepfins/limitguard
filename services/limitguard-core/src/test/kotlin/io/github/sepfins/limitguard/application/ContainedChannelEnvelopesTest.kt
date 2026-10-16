package io.github.sepfins.limitguard.application

import io.github.sepfins.limitguard.api.LimitAmount
import io.github.sepfins.limitguard.api.LimitChannelScope
import io.github.sepfins.limitguard.api.LimitRuleChangeStatus
import io.github.sepfins.limitguard.api.LimitKind
import io.github.sepfins.limitguard.api.LimitPeriod
import io.github.sepfins.limitguard.api.commands.CreateChannelGroupCommand
import io.github.sepfins.limitguard.api.errors.ValidationException
import io.github.sepfins.limitguard.api.errors.NotFoundException
import io.github.sepfins.limitguard.support.FixedClockProvider
import io.github.sepfins.limitguard.support.FundingEnvelopeScenario
import io.github.sepfins.limitguard.support.PostgresFacade
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
            child.copy(maxAssignableAmount = LimitAmount.Bounded(BigDecimal("1001"))),
            child.copy(maxAssignableAmount = LimitAmount.Bounded(BigDecimal("500")), defaultAmount = BigDecimal("501")),
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
        val parent = s.configuration("PARENT")
        val selected = s.configuration("SELECTED", parent.id, channelGroupId = group.id)

        assertFailsWith<ValidationException> { s.facade.limitChangeService.requestLimitRuleChange(s.requestCommand(selected, "1001")) }
        val request = s.facade.limitChangeService.requestLimitRuleChange(s.requestCommand(selected))
        assertEquals(s.channel, request.requestChannelId)
        val applied = s.approve(request.id)
        assertEquals(LimitRuleChangeStatus.APPLIED, applied.status)
        assertEquals(group.id, s.rule(requireNotNull(applied.limitRuleId)).channelGroupId)
    }

    private fun scenario() =
        FundingEnvelopeScenario(PostgresFacade.create(FixedClockProvider(NOW), emptyList()), NOW)

    companion object {
        private val NOW = Instant.parse("2026-10-10T10:00:00Z")
    }
}
