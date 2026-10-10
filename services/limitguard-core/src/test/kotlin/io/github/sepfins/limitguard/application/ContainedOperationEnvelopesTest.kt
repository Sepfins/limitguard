package io.github.sepfins.limitguard.application

import io.github.sepfins.limitguard.api.LimitKind
import io.github.sepfins.limitguard.api.LimitPeriod
import io.github.sepfins.limitguard.api.commands.UpdateLimitConfigurationCommand
import io.github.sepfins.limitguard.api.commands.SetLimitConfigurationActiveCommand
import io.github.sepfins.limitguard.api.errors.ValidationException
import io.github.sepfins.limitguard.api.errors.NotFoundException
import io.github.sepfins.limitguard.api.errors.LimitguardException
import io.github.sepfins.limitguard.api.errors.LimitguardErrorCode
import io.github.sepfins.limitguard.domain.common.LimitConfigurationId
import io.github.sepfins.limitguard.domain.common.OperationTypeId
import io.github.sepfins.limitguard.domain.common.TenantId
import io.github.sepfins.limitguard.domain.limit.LimitOperationScope
import io.github.sepfins.limitguard.support.FixedClockProvider
import io.github.sepfins.limitguard.support.FundingEnvelopeScenario
import io.github.sepfins.limitguard.support.createFacade
import io.github.sepfins.limitguard.support.persistence.InMemoryLimitguardRepositories
import java.math.BigDecimal
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ContainedOperationEnvelopesTest {
    @Test
    fun `sibling operation subtrees cannot share a narrow assignment envelope even before descendants exist`() {
        val s = scenario()
        val root = s.registerOperation("ROOT")
        val transfer = s.registerOperation("TRANSFER", root.id)
        val cash = s.registerOperation("CASH", root.id)
        val parent = s.configuration("PARENT", operationId = transfer.id)

        val failure = assertFailsWith<ValidationException> {
            s.configuration("CHILD", parent.id, operationId = cash.id)
        }

        assertTrue(failure.message.orEmpty().contains("operation coverage"))
        assertNull(s.findConfiguration("CHILD"))
    }

    @Test
    fun `all self ancestor descendant sibling and unrelated selectors use live subtree coverage`() {
        val s = scenario()
        val root = s.registerOperation("ROOT")
        val branch = s.registerOperation("BRANCH", root.id)
        val leaf = s.registerOperation("LEAF", branch.id, leaf = true)
        val sibling = s.registerOperation("SIBLING", root.id)
        val unrelated = s.registerOperation("UNRELATED")
        val selectors = listOf(null, root.id, branch.id, leaf.id, sibling.id, unrelated.id)
        val expected = listOf(
            listOf(true, true, true, true, true, true),
            listOf(false, true, true, true, true, false),
            listOf(false, false, true, true, false, false),
            listOf(false, false, false, true, false, false),
            listOf(false, false, false, false, true, false),
            listOf(false, false, false, false, false, true),
        )
        val parents = selectors.mapIndexed { index, selector -> s.configuration("PARENT_$index", operationId = selector) }

        parents.forEachIndexed { row, parent ->
            selectors.forEachIndexed { column, selector ->
                val code = "CHILD_${row}_$column"
                if (expected[row][column]) {
                    assertEquals(parent.id, s.configuration(code, parent.id, operationId = selector).parentConfigurationId)
                } else {
                    assertFailsWith<ValidationException> { s.configuration(code, parent.id, operationId = selector) }
                    assertNull(s.findConfiguration(code))
                }
            }
        }
    }

    @Test
    fun `all remains broad while childless operation selectors contain themselves and future descendants`() {
        val s = scenario()
        val all = s.configuration("ALL", active = false)
        assertEquals(all.id, s.configuration("EMPTY_CATALOG", all.id).parentConfigurationId)
        val root = s.registerOperation("ROOT", global = true)
        val narrow = s.configuration("NARROW", all.id, operationId = root.id, active = false)
        val child = s.configuration("CHILDLESS", narrow.id, operationId = root.id, active = false)

        assertFailsWith<ValidationException> { s.configuration("INVALID_ALL", child.id) }
        assertNull(s.findConfiguration("INVALID_ALL"))
        val global = s.registerOperation("GLOBAL", root.id, global = true)
        val local = s.registerOperation("LOCAL", global.id)
        val leaf = s.registerOperation("LEAF", local.id, leaf = true)
        assertEquals(child.id, s.configuration("FUTURE", child.id, operationId = leaf.id).parentConfigurationId)
        assertEquals(root.id, s.findConfiguration("CHILDLESS")?.operationTypeId)
    }

    @Test
    fun `operation envelopes use global and local identities rather than matching codes or another tenants nodes`() {
        val s = scenario()
        val other = FundingEnvelopeScenario(s.facade, NOW)
        val global = s.registerOperation("TRANSFER", global = true)
        val local = s.registerOperation("TRANSFER")
        val descendant = s.registerOperation("INSTANT", global.id)
        val foreign = other.registerOperation("FOREIGN", global.id)
        val parent = s.configuration("PARENT", operationId = global.id)
        val foreignParent = other.configuration("PARENT", operationId = global.id)

        assertEquals(parent.id, s.configuration("DESCENDANT", parent.id, operationId = descendant.id).parentConfigurationId)
        assertEquals(foreignParent.id, other.configuration("LOCAL_CHILD", foreignParent.id, operationId = foreign.id).parentConfigurationId)
        assertFailsWith<ValidationException> { s.configuration("INVALID", parent.id, operationId = local.id) }
        assertFailsWith<NotFoundException> { s.configuration("INVALID", parent.id, operationId = foreign.id) }
        assertFailsWith<NotFoundException> { other.configuration("INVALID", foreignParent.id, operationId = descendant.id) }
        assertFailsWith<NotFoundException> { s.configuration("INVALID", foreignParent.id, operationId = global.id) }
        assertNull(s.findConfiguration("INVALID"))
        assertNull(other.findConfiguration("INVALID"))
        assertEquals(setOf(global.id, local.id), s.findOperations("TRANSFER").map { it.id }.toSet())
    }

    @Test
    fun `descriptive updates report invalid operation coverage beyond the immediate parent without widening it`() {
        val repositories = InMemoryLimitguardRepositories()
        val s = scenario(repositories)
        val transfer = s.registerOperation("TRANSFER")
        val cash = s.registerOperation("CASH")
        val root = s.configuration("ROOT")
        val parent = s.configuration("PARENT", root.id, operationId = transfer.id)
        val child = s.configuration("CHILD", parent.id, operationId = transfer.id, active = false)
        val tenant = TenantId.of(s.tenant)
        val stored = requireNotNull(repositories.limitConfigurations.findById(tenant, LimitConfigurationId.of(root.id)))
        repositories.limitConfigurations.update(tenant, stored.copy(
            operationScope = LimitOperationScope.OperationTypeSpecific(OperationTypeId.of(cash.id)),
        ))
        val invalidRoot = s.findConfiguration("ROOT")

        assertFailsWith<ValidationException> { s.configuration("NEW", child.id, operationId = transfer.id) }
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
    fun `activation and no-op deactivation reject invalid disabled operation envelopes`() {
        val repositories = InMemoryLimitguardRepositories()
        val s = scenario(repositories)
        val transfer = s.registerOperation("TRANSFER")
        val cash = s.registerOperation("CASH")
        val parent = s.configuration("PARENT", operationId = transfer.id)
        val child = s.configuration("CHILD", parent.id, operationId = transfer.id, active = false)
        val tenant = TenantId.of(s.tenant)
        val stored = requireNotNull(repositories.limitConfigurations.findById(tenant, LimitConfigurationId.of(parent.id)))
        repositories.limitConfigurations.update(tenant, stored.copy(
            operationScope = LimitOperationScope.OperationTypeSpecific(OperationTypeId.of(cash.id)),
        ))

        for (active in listOf(true, false)) {
            val failure = assertFailsWith<ValidationException> {
                s.facade.tenantCatalogService.setLimitConfigurationActive(SetLimitConfigurationActiveCommand(s.tenant, child.id, active))
            }
            assertTrue(failure.message.orEmpty().contains("correct the operation envelope explicitly"))
            assertEquals(child, s.findConfiguration("CHILD"))
        }
    }

    @Test
    fun `global descendant registration reports invalid disabled tenant envelopes atomically`() {
        val repositories = InMemoryLimitguardRepositories()
        val s = scenario(repositories)
        val other = FundingEnvelopeScenario(s.facade, NOW)
        val transfer = s.registerOperation("TRANSFER", global = true)
        val cash = s.registerOperation("CASH", global = true)
        val local = s.registerOperation("LOCAL", transfer.id)
        val parent = s.configuration("PARENT", operationId = transfer.id, active = false)
        val child = s.configuration("CHILD", parent.id, operationId = local.id, active = false)
        val tenant = TenantId.of(s.tenant)
        val stored = requireNotNull(repositories.limitConfigurations.findById(tenant, LimitConfigurationId.of(parent.id)))
        repositories.limitConfigurations.update(tenant, stored.copy(
            operationScope = LimitOperationScope.OperationTypeSpecific(OperationTypeId.of(cash.id)),
        ))
        val invalidParent = s.findConfiguration("PARENT")

        val failure = assertFailsWith<ValidationException> {
            other.registerOperation("NEW_GLOBAL", transfer.id, global = true)
        }

        assertTrue(failure.message.orEmpty().contains(child.id.toString()))
        assertEquals(emptyList(), s.findOperations("NEW_GLOBAL"))
        assertEquals(emptyList(), other.findOperations("NEW_GLOBAL"))
        assertEquals(invalidParent, s.findConfiguration("PARENT"))
        assertEquals(child, s.findConfiguration("CHILD"))
        repositories.limitConfigurations.update(tenant, stored)
        val registered = other.registerOperation("NEW_GLOBAL", transfer.id, global = true)
        assertEquals(listOf(registered), s.findOperations("NEW_GLOBAL"))
        assertEquals(listOf(registered), other.findOperations("NEW_GLOBAL"))
    }

    @Test
    fun `local growth revalidates disabled local chains but not another tenants private envelope`() {
        val repositories = InMemoryLimitguardRepositories()
        val s = scenario(repositories)
        val other = FundingEnvelopeScenario(s.facade, NOW)
        val global = s.registerOperation("GLOBAL", global = true)
        val local = s.registerOperation("LOCAL", global.id)
        val unrelated = s.registerOperation("UNRELATED")
        val parent = s.configuration("PARENT", operationId = global.id, active = false)
        val child = s.configuration("CHILD", parent.id, operationId = local.id, active = false)
        val tenant = TenantId.of(s.tenant)
        val stored = requireNotNull(repositories.limitConfigurations.findById(tenant, LimitConfigurationId.of(parent.id)))
        repositories.limitConfigurations.update(tenant, stored.copy(
            operationScope = LimitOperationScope.OperationTypeSpecific(OperationTypeId.of(unrelated.id)),
        ))

        assertEquals(global.id, other.registerOperation("OTHER_LOCAL", global.id).parentId)
        assertFailsWith<ValidationException> { s.registerOperation("NEW_LOCAL", local.id) }
        assertEquals(emptyList(), s.findOperations("NEW_LOCAL"))
        assertEquals(child, s.findConfiguration("CHILD"))
    }

    @Test
    fun `missing hidden cyclic global-private and stale leaf ancestry fail explicitly even for identical selectors`() {
        for (fault in listOf("missing", "hidden", "cycle", "global-private", "leaf")) {
            val repositories = InMemoryLimitguardRepositories()
            val s = scenario(repositories)
            val other = FundingEnvelopeScenario(s.facade, NOW)
            val root = s.registerOperation("ROOT", global = fault == "global-private")
            val selected = s.registerOperation("SELECTED", root.id)
            val privateRoot = s.registerOperation("PRIVATE")
            val foreign = other.registerOperation("FOREIGN")
            val parent = s.configuration("PARENT", operationId = root.id)
            val existing = s.configuration("EXISTING", parent.id, operationId = selected.id)
            val tenant = TenantId.of(s.tenant)
            val stored = requireNotNull(repositories.operationTypes.findById(tenant, OperationTypeId.of(root.id)))
            val corrupt = when (fault) {
                "missing" -> stored.copy(parentId = OperationTypeId.of(java.util.UUID.randomUUID()))
                "hidden" -> stored.copy(parentId = OperationTypeId.of(foreign.id))
                "cycle" -> stored.copy(parentId = OperationTypeId.of(selected.id))
                "global-private" -> stored.copy(parentId = OperationTypeId.of(privateRoot.id))
                else -> stored.copy(isLeaf = true)
            }
            repositories.operationTypes.update(tenant, corrupt)
            val expected = if (fault == "missing" || fault == "hidden") LimitguardErrorCode.NOT_FOUND else LimitguardErrorCode.CONFLICT

            val admission = assertFailsWith<LimitguardException> {
                s.configuration("INVALID", existing.id, operationId = selected.id)
            }
            assertEquals(expected, admission.errorCode, fault)
            assertNull(s.findConfiguration("INVALID"))
            val growth = assertFailsWith<LimitguardException> { s.registerOperation("NEW", selected.id) }
            assertEquals(expected, growth.errorCode, fault)
            assertEquals(emptyList(), s.findOperations("NEW"))
            assertEquals(existing, s.findConfiguration("EXISTING"))
        }
    }

    @Test
    fun `operation containment retains maximum default currency kind period and effective window constraints`() {
        val s = scenario()
        val root = s.registerOperation("ROOT")
        val selected = s.registerOperation("SELECTED", root.id)
        val parentCommand = s.configurationCommand("PARENT", operationId = root.id).copy(endsAt = NOW.plusSeconds(3600))
        val parent = s.facade.tenantCatalogService.createLimitConfiguration(parentCommand)
        val child = s.configurationCommand("CHILD", parent.id, operationId = selected.id).copy(endsAt = parent.endsAt)

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
        assertEquals(parent.id, s.facade.tenantCatalogService.createLimitConfiguration(child).parentConfigurationId)
        val daily = s.facade.tenantCatalogService.createLimitConfiguration(parentCommand.copy(
            code = "DAILY", limitKind = LimitKind.PERIODIC, period = LimitPeriod.DAILY,
        ))
        assertFailsWith<ValidationException> {
            s.facade.tenantCatalogService.createLimitConfiguration(child.copy(
                code = "MONTHLY", parentConfigurationId = daily.id, limitKind = LimitKind.PERIODIC, period = LimitPeriod.MONTHLY,
            ))
        }
    }

    private fun scenario(repositories: InMemoryLimitguardRepositories = InMemoryLimitguardRepositories()) =
        FundingEnvelopeScenario(repositories.createFacade(FixedClockProvider(NOW), emptyList()), NOW)

    companion object {
        private val NOW = Instant.parse("2026-10-10T10:00:00Z")
    }
}
