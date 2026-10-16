package io.github.sepfins.limitguard.application

import io.github.sepfins.limitguard.api.LimitAmount
import io.github.sepfins.limitguard.api.FundingSourceCategory
import io.github.sepfins.limitguard.api.AuthorizationDecision
import io.github.sepfins.limitguard.api.LimitRuleChangeStatus
import io.github.sepfins.limitguard.api.LimitRuleChangeType
import io.github.sepfins.limitguard.api.LimitChannelScope
import io.github.sepfins.limitguard.api.LimitKind
import io.github.sepfins.limitguard.api.LimitPeriod
import io.github.sepfins.limitguard.api.LimitChangePolicyDecision
import io.github.sepfins.limitguard.api.LimitChangePolicyPhase
import io.github.sepfins.limitguard.api.commands.CreateLimitChangePolicyCommand
import io.github.sepfins.limitguard.api.commands.UpdateLimitConfigurationCommand
import io.github.sepfins.limitguard.api.errors.ValidationException
import io.github.sepfins.limitguard.api.errors.NotFoundException
import io.github.sepfins.limitguard.api.errors.ConflictException
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

class ContainedFundingEnvelopesTest {
    @Test
    fun `all funding cannot fit a category envelope even with an empty catalog`() {
        val s = scenario()
        val parent = s.configuration("CATEGORY", category = FundingSourceCategory.TRANSACTIONAL)

        assertFailsWith<ValidationException> { s.configuration("ALL", parent.id) }

        assertNull(s.findConfiguration("ALL"))
        assertEquals(parent, s.findConfiguration("CATEGORY"))
    }

    @Test
    fun `product coverage uses actual leaf categories including inactive leaves not the family label`() {
        val s = scenario()
        val family = s.product("FAMILY")
        s.product("CURRENT_ACCOUNT", family.id, leaf = true)
        val savings = s.product("SAVINGS_ACCOUNT", family.id, FundingSourceCategory.SAVINGS, leaf = true, active = false)
        val parent = s.configuration("CATEGORY", category = FundingSourceCategory.TRANSACTIONAL)

        assertFailsWith<ValidationException> { s.configuration("MIXED", parent.id, product = family.id) }

        assertNull(s.findConfiguration("MIXED"))
        assertEquals(savings, s.findProduct("SAVINGS_ACCOUNT"))
    }

    @Test
    fun `disabled empty product envelopes are revalidated before catalog growth`() {
        val s = scenario()
        val family = s.product("FAMILY")
        val parent = s.configuration("CATEGORY", category = FundingSourceCategory.TRANSACTIONAL, active = false)
        val child = s.configuration("PRODUCT", parent.id, product = family.id, active = false)
        val branch = s.product("BRANCH", family.id, FundingSourceCategory.SAVINGS, active = false)

        assertFailsWith<ValidationException> {
            s.product("OUTSIDE", branch.id, FundingSourceCategory.SAVINGS, leaf = true, active = false)
        }

        assertNull(s.findProduct("OUTSIDE"))
        assertEquals(child, s.findConfiguration("PRODUCT"))
        assertEquals(branch.id, s.product("OUTSIDE", branch.id, leaf = true).parentId)
    }

    @Test
    fun `all category and product selectors compare live coverage in both directions`() {
        val s = scenario()
        val family = s.product("FAMILY", category = FundingSourceCategory.SAVINGS)
        val branch = s.product("BRANCH", family.id, FundingSourceCategory.CREDIT)
        s.product("INSIDE", branch.id, leaf = true)
        s.product("FAMILY_LEAF", family.id, leaf = true)
        s.product("OUTSIDE", leaf = true)
        s.product("SAVINGS", category = FundingSourceCategory.SAVINGS, leaf = true)
        val selectors = listOf(
            s.configurationCommand("ALL"),
            s.configurationCommand("CATEGORY", category = FundingSourceCategory.TRANSACTIONAL),
            s.configurationCommand("PRODUCT", product = family.id),
        )
        val expected = listOf(listOf(true, true, true), listOf(false, true, true), listOf(false, false, true))
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
        val familyEnvelope = parents[2]
        val branchEnvelope = s.configuration("BRANCH_SCOPE", familyEnvelope.id, product = branch.id)
        assertFailsWith<ValidationException> { s.configuration("REVERSE", branchEnvelope.id, product = family.id) }
        assertNull(s.findConfiguration("REVERSE"))
    }

    @Test
    fun `same empty selectors contain themselves but all funding never fits an empty product envelope`() {
        val s = scenario()
        val family = s.product("EMPTY")
        val all = s.configuration("ALL")
        val category = s.configuration("CATEGORY", all.id, category = FundingSourceCategory.TRANSACTIONAL)
        val product = s.configuration("PRODUCT", all.id, product = family.id)

        assertEquals(all.id, s.configuration("ALL_CHILD", all.id).parentConfigurationId)
        assertEquals(category.id, s.configuration("CATEGORY_CHILD", category.id, category = FundingSourceCategory.TRANSACTIONAL).parentConfigurationId)
        assertEquals(product.id, s.configuration("PRODUCT_CHILD", product.id, product = family.id).parentConfigurationId)
        assertFailsWith<ValidationException> { s.configuration("BROAD", product.id) }
        assertNull(s.findConfiguration("BROAD"))
    }

    @Test
    fun `empty category coverage is vacuous under a product and is revalidated for root growth`() {
        val s = scenario()
        val family = s.product("FAMILY")
        val parent = s.configuration("PRODUCT", product = family.id)
        val child = s.configuration("CATEGORY", parent.id, category = FundingSourceCategory.SAVINGS)

        assertFailsWith<ValidationException> { s.product("OUTSIDE", category = FundingSourceCategory.SAVINGS, leaf = true) }
        assertNull(s.findProduct("OUTSIDE"))
        val inside = s.product("INSIDE", family.id, FundingSourceCategory.SAVINGS, leaf = true)
        assertEquals(child, s.findConfiguration("CATEGORY"))
        assertEquals(family.id, inside.parentId)
    }

    @Test
    fun `an empty disjoint product is contained until it gains an assignable leaf`() {
        val s = scenario()
        val covered = s.product("COVERED")
        s.product("ACCOUNT", covered.id, leaf = true)
        val empty = s.product("EMPTY")
        val parent = s.configuration("PARENT", product = covered.id)
        val child = s.configuration("CHILD", parent.id, product = empty.id)
        val branch = s.product("BRANCH", empty.id)

        assertFailsWith<ValidationException> { s.product("OUTSIDE", branch.id, leaf = true) }
        assertNull(s.findProduct("OUTSIDE"))
        assertEquals(child, s.findConfiguration("CHILD"))
    }

    @Test
    fun `different empty categories are vacuously contained and reject incompatible future coverage`() {
        val s = scenario()
        val parent = s.configuration("PARENT", category = FundingSourceCategory.TRANSACTIONAL)
        val child = s.configuration("CHILD", parent.id, category = FundingSourceCategory.SAVINGS)

        s.product("CURRENT_ACCOUNT", leaf = true)
        assertFailsWith<ValidationException> { s.product("SAVINGS", category = FundingSourceCategory.SAVINGS, leaf = true) }
        assertNull(s.findProduct("SAVINGS"))
        assertEquals(child, s.findConfiguration("CHILD"))
    }

    @Test
    fun `configuration funding references and envelopes remain tenant scoped`() {
        val s = scenario()
        val other = FundingEnvelopeScenario(s.facade, NOW)
        val family = other.product("FAMILY")
        val parent = other.configuration("PARENT", category = FundingSourceCategory.SAVINGS)
        assertFailsWith<NotFoundException> { s.configuration("FOREIGN_PRODUCT", product = family.id) }
        assertFailsWith<NotFoundException> { s.configuration("FOREIGN_PARENT", parent.id) }
        assertNull(s.findConfiguration("FOREIGN_PRODUCT"))
        assertNull(s.findConfiguration("FOREIGN_PARENT"))

        s.product("SAVINGS", category = FundingSourceCategory.SAVINGS, leaf = true)
        assertEquals(parent, other.findConfiguration("PARENT"))
    }

    @Test
    fun `funding containment retains kind period currency caps defaults and effective window checks`() {
        val s = scenario()
        val rootCommand = s.configurationCommand("ROOT").copy(endsAt = NOW.plusSeconds(3600))
        val root = s.facade.tenantCatalogService.createLimitConfiguration(rootCommand)
        val child = s.configurationCommand("CHILD", root.id, category = FundingSourceCategory.TRANSACTIONAL)
            .copy(endsAt = root.endsAt)
        val invalid = listOf(
            child.copy(currency = "USD"),
            child.copy(maxAssignableAmount = LimitAmount.Bounded(BigDecimal("1001"))),
            child.copy(maxAssignableAmount = LimitAmount.Bounded(BigDecimal("500")), defaultAmount = BigDecimal("501")),
            child.copy(defaultAmount = BigDecimal.ZERO),
            child.copy(startsAt = NOW.minusSeconds(1)),
            child.copy(endsAt = null),
            child.copy(endsAt = NOW.plusSeconds(3601)),
            child.copy(limitKind = LimitKind.PERIODIC, period = LimitPeriod.DAILY),
        )
        invalid.forEach { command ->
            assertFailsWith<ValidationException> { s.facade.tenantCatalogService.createLimitConfiguration(command) }
            assertNull(s.findConfiguration("CHILD"))
        }
        assertEquals(root.id, s.facade.tenantCatalogService.createLimitConfiguration(child).parentConfigurationId)
        val periodic = s.facade.tenantCatalogService.createLimitConfiguration(rootCommand.copy(
            code = "DAILY", limitKind = LimitKind.PERIODIC, period = LimitPeriod.DAILY,
        ))
        assertFailsWith<ValidationException> {
            s.facade.tenantCatalogService.createLimitConfiguration(child.copy(
                code = "MONTHLY", parentConfigurationId = periodic.id, limitKind = LimitKind.PERIODIC, period = LimitPeriod.MONTHLY,
            ))
        }
    }

    @Test
    fun `a dated configuration grants a rule within its maximum`() {
        val s = scenario()
        val configuration = s.facade.tenantCatalogService.createLimitConfiguration(s.configurationCommand(
            "SCOPED", category = FundingSourceCategory.TRANSACTIONAL,
        ).copy(channelScope = LimitChannelScope.CHANNEL, channelId = s.channel, operationTypeId = s.operation,
            priority = 7, endsAt = NOW.plusSeconds(3600)))
        assertFailsWith<ValidationException> { s.facade.limitChangeService.requestLimitRuleChange(s.requestCommand(configuration, "1001")) }
        assertEquals(LimitRuleChangeStatus.REQUESTED, s.facade.limitChangeService.requestLimitRuleChange(s.requestCommand(configuration)).status)
    }

    @Test
    fun `equivalent selector values retain target identity while broad and narrow granted targets coexist`() {
        val s = scenario()
        val family = s.product("FAMILY")
        val leaf = s.product("ACCOUNT", family.id, leaf = true)
        val source = s.source(leaf.id)
        val all = s.configuration("ALL")
        val category = s.configuration("CATEGORY", all.id, category = FundingSourceCategory.TRANSACTIONAL)
        val product = s.configuration("PRODUCT", category.id, product = family.id)
        val broad = s.grant(all, "500")
        val categoryRule = s.grant(category, "300")
        val productRule = s.grant(product, "200")

        val equivalent = listOf(
            s.configuration("ALL_EQUIVALENT"),
            s.configuration("CATEGORY_EQUIVALENT", category = FundingSourceCategory.TRANSACTIONAL),
            s.configuration("PRODUCT_EQUIVALENT", product = family.id),
        )
        equivalent.forEach { configuration ->
            assertFailsWith<ConflictException> { s.facade.limitChangeService.requestLimitRuleChange(s.requestCommand(configuration)) }
        }
        val updated = s.facade.tenantCatalogService.updateLimitConfiguration(UpdateLimitConfigurationCommand(
            s.tenant, product.id, "Renamed", "Description", BigDecimal("150"), mapOf("version" to 2),
        ))
        assertEquals(product.copy(name = "Renamed", description = "Description", defaultAmount = BigDecimal("150.0000"),
            metadata = mapOf("version" to 2), updatedAt = NOW), updated)
        assertEquals(productRule, s.rule(productRule.id))
        val amountChange = s.facade.limitChangeService.requestLimitRuleChange(s.requestCommand(updated))
        assertEquals(LimitRuleChangeType.UPDATE, amountChange.changeType)
        assertEquals(productRule.id, amountChange.limitRuleId)
        val allowed = s.authorize(source, "180")
        assertEquals(AuthorizationDecision.ALLOWED, allowed.decision)
        assertEquals(setOf(broad.id, categoryRule.id, productRule.id), allowed.matchedRules.map { it.id }.toSet())
        assertEquals(listOf(productRule.id), s.authorize(source, "220").violations.mapNotNull { it.limitRuleId })
    }

    @Test
    fun `defaults suggest amounts without granted ancestor rules or inherited policies`() {
        val s = FundingEnvelopeScenario(PostgresFacade.create(FixedClockProvider(NOW)), NOW)
        val leaf = s.product("ACCOUNT", leaf = true)
        val source = s.source(leaf.id)
        val parent = s.facade.tenantCatalogService.createLimitConfiguration(s.configurationCommand("PARENT")
            .copy(defaultAmount = BigDecimal.ONE))
        val child = s.facade.tenantCatalogService.createLimitConfiguration(s.configurationCommand("CHILD", parent.id)
            .copy(defaultAmount = BigDecimal.ONE))
        s.facade.tenantCatalogService.createLimitChangePolicy(CreateLimitChangePolicyCommand(
            tenantId = s.tenant, code = "PARENT_DENY", name = "Parent denial", phase = LimitChangePolicyPhase.AUTHORIZATION,
            decision = LimitChangePolicyDecision.DENY, limitConfigurationId = parent.id,
        ))

        val ungranted = s.authorize(source, "40")
        assertEquals(AuthorizationDecision.DENIED, ungranted.decision)
        assertTrue(ungranted.matchedRules.isEmpty())
        val request = s.facade.limitChangeService.requestLimitRuleChange(s.requestCommand(child))
        assertEquals(LimitRuleChangeStatus.REQUESTED, request.status)
        val rule = s.rule(requireNotNull(s.approve(request.id).limitRuleId))
        assertEquals(LimitAmount.Bounded(BigDecimal("500.0000")), rule.amount)
        val allowed = s.authorize(source, "400")
        assertEquals(AuthorizationDecision.ALLOWED, allowed.decision)
        assertEquals(listOf(rule.id), allowed.matchedRules.map { it.id })
    }

    private fun scenario() =
        FundingEnvelopeScenario(PostgresFacade.create(FixedClockProvider(NOW), emptyList()), NOW)

    companion object {
        private val NOW = Instant.parse("2026-10-10T10:00:00Z")
    }
}
