package io.github.sepfins.limitguard.application

import io.github.sepfins.limitguard.api.LimitAmount
import io.github.sepfins.limitguard.api.LimitKind
import io.github.sepfins.limitguard.api.LimitPeriod
import io.github.sepfins.limitguard.api.commands.MoveOperationTypeCommand
import io.github.sepfins.limitguard.api.errors.ValidationException
import io.github.sepfins.limitguard.api.errors.NotFoundException
import io.github.sepfins.limitguard.support.FixedClockProvider
import io.github.sepfins.limitguard.support.FundingEnvelopeScenario
import io.github.sepfins.limitguard.support.PostgresFacade
import io.github.sepfins.limitguard.support.execute
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
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
    fun `local growth revalidates disabled local chains but not another tenants private envelope`() {
        val database = PostgresFacade.database()
        val s = FundingEnvelopeScenario(PostgresFacade.create(FixedClockProvider(NOW), emptyList(), database), NOW)
        val other = FundingEnvelopeScenario(s.facade, NOW)
        val global = s.registerOperation("GLOBAL", global = true)
        val local = s.registerOperation("LOCAL", global.id)
        val unrelated = s.registerOperation("UNRELATED")
        val parent = s.configuration("PARENT", operationId = global.id, active = false)
        val child = s.configuration("CHILD", parent.id, operationId = local.id, active = false)
        database.execute("update limit_configuration set operation_type_id = ? where id = ?", unrelated.id, parent.id)

        assertEquals(global.id, other.registerOperation("OTHER_LOCAL", global.id).parentId)
        assertFailsWith<ValidationException> { s.registerOperation("NEW_LOCAL", local.id) }
        assertEquals(emptyList(), s.findOperations("NEW_LOCAL"))
        assertEquals(child, s.findConfiguration("CHILD"))
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

    @Test
    fun `moving an operation keeps every configuration envelope contained`() {
        val s = scenario()
        val root = s.registerOperation("ROOT")
        val transfer = s.registerOperation("TRANSFER", root.id)
        val cash = s.registerOperation("CASH", root.id)
        val instant = s.registerOperation("INSTANT", transfer.id, leaf = true)
        val parent = s.configuration("PARENT", operationId = transfer.id)
        s.configuration("CHILD", parent.id, operationId = instant.id)

        val failure = assertFailsWith<ValidationException> { s.move(instant.id, cash.id) }

        assertTrue(failure.message.orEmpty().contains("operation coverage"))
        assertEquals(transfer.id, s.findOperations("INSTANT").single().parentId)
        val sepa = s.registerOperation("SEPA", transfer.id)
        assertEquals(sepa.id, s.move(instant.id, sepa.id).parentId)
    }

    @Test
    fun `a move cannot land under a leaf or its own subtree and leaves global types alone`() {
        val s = scenario()
        val branch = s.registerOperation("BRANCH")
        val child = s.registerOperation("CHILD", branch.id)
        val leaf = s.registerOperation("LEAF", leaf = true)
        val global = s.registerOperation("GLOBAL", global = true)

        assertFailsWith<ValidationException> { s.move(child.id, leaf.id) }
        assertFailsWith<ValidationException> { s.move(branch.id, child.id) }
        assertFailsWith<ValidationException> { s.move(branch.id, branch.id) }
        assertFailsWith<ValidationException> { s.move(global.id, branch.id) }
        assertEquals(branch.id, s.findOperations("CHILD").single().parentId)
        assertEquals(global.id, s.move(child.id, global.id).parentId)
        assertNull(s.move(child.id, null).parentId)
    }

    private fun FundingEnvelopeScenario.move(operationId: UUID, parentId: UUID?) =
        facade.tenantCatalogService.moveOperationType(MoveOperationTypeCommand(tenant, operationId, parentId))

    private fun scenario() =
        FundingEnvelopeScenario(PostgresFacade.create(FixedClockProvider(NOW), emptyList()), NOW)

    companion object {
        private val NOW = Instant.parse("2026-10-10T10:00:00Z")
    }
}
