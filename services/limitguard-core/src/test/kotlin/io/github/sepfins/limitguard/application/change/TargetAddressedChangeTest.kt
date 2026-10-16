package io.github.sepfins.limitguard.application.change

import io.github.sepfins.limitguard.api.AuthorizationDecision
import io.github.sepfins.limitguard.api.LimitAmount
import io.github.sepfins.limitguard.api.LimitRuleChangeStatus
import io.github.sepfins.limitguard.api.LimitRuleChangeType
import io.github.sepfins.limitguard.api.commands.LimitRuleChangeOperation
import io.github.sepfins.limitguard.api.errors.ConflictException
import io.github.sepfins.limitguard.api.errors.NotFoundException
import io.github.sepfins.limitguard.api.queries.GetLimitRuleQuery
import io.github.sepfins.limitguard.support.FixedClockProvider
import io.github.sepfins.limitguard.support.OperationSubtreeScenario
import io.github.sepfins.limitguard.support.PostgresFacade
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class TargetAddressedChangeTest {
    @Test
    fun `set amount creates the rule for an empty target and afterwards changes only its amount`() {
        val s = scenario()
        val transfer = s.operation("TRANSFER", leaf = true)
        val configuration = s.configuration(transfer)
        val created = s.approve(s.request(configuration, setAmount("500")).id)
        val ruleId = requireNotNull(created.limitRuleId)
        assertEquals(LimitRuleChangeType.CREATE, created.changeType)
        assertEquals(AuthorizationDecision.ALLOWED, s.authorize(transfer, "40").decision)

        val lowered = s.approve(s.request(configuration, setAmount("300"), expectedVersion = 1).id)

        assertEquals(LimitRuleChangeType.UPDATE, lowered.changeType)
        assertEquals(ruleId, lowered.limitRuleId)
        assertEquals(LimitAmount.Bounded(BigDecimal("300.0000")), rule(s, ruleId).amount)
        assertEquals(2, rule(s, ruleId).version)
        assertEquals(BigDecimal("40.0000"), s.usage(ruleId).reservedAmount)
        assertEquals(AuthorizationDecision.DENIED, s.authorize(transfer, "261").decision)

        s.approve(s.request(configuration, LimitRuleChangeOperation.Deactivate, expectedVersion = 2).id)
        s.approve(s.request(configuration, setAmount("400"), expectedVersion = 3).id)

        val inactive = rule(s, ruleId)
        assertEquals(false, inactive.isActive)
        assertEquals(LimitAmount.Bounded(BigDecimal("400.0000")), inactive.amount)
        assertEquals(4, inactive.version)
    }

    @Test
    fun `deactivate reactivate and delete address the rule by target`() {
        val s = scenario()
        val transfer = s.operation("TRANSFER", leaf = true)
        val configuration = s.configuration(transfer, perTransaction = true)
        assertFailsWith<NotFoundException> { s.request(configuration, LimitRuleChangeOperation.Deactivate) }
        val ruleId = s.rule(configuration, LimitAmount.Bounded(BigDecimal("100")))

        assertFailsWith<ConflictException> { s.request(configuration, LimitRuleChangeOperation.Deactivate, expectedVersion = 2) }
        assertEquals(LimitRuleChangeType.DEACTIVATE, s.approve(s.request(configuration, LimitRuleChangeOperation.Deactivate).id).changeType)
        assertEquals(AuthorizationDecision.ALLOWED, s.authorize(transfer, "150").decision)
        assertFailsWith<ConflictException> { s.request(configuration, LimitRuleChangeOperation.Deactivate) }
        assertEquals(LimitRuleChangeType.REACTIVATE, s.approve(s.request(configuration, LimitRuleChangeOperation.Reactivate).id).changeType)
        assertEquals(AuthorizationDecision.DENIED, s.authorize(transfer, "150").decision)
        val deleted = s.approve(s.request(configuration, LimitRuleChangeOperation.Delete).id)
        assertEquals(LimitRuleChangeType.DELETE, deleted.changeType)
        assertEquals(LimitRuleChangeStatus.APPLIED, deleted.status)

        val recreated = s.approve(s.request(configuration, setAmount("200")).id)

        assertEquals(LimitRuleChangeType.CREATE, recreated.changeType)
        assertNotEquals(ruleId, recreated.limitRuleId)
        assertEquals(false, rule(s, ruleId).isActive)
    }

    private fun setAmount(amount: String) = LimitRuleChangeOperation.SetAmount(LimitAmount.Bounded(BigDecimal(amount)))

    private fun rule(s: OperationSubtreeScenario, id: UUID) =
        s.facade.limitInquiryService.getLimitRule(GetLimitRuleQuery(s.tenant, id))

    private fun scenario() = OperationSubtreeScenario(PostgresFacade.create(FixedClockProvider(NOW), emptyList()), NOW)

    private companion object {
        val NOW: Instant = Instant.parse("2026-06-26T12:00:00Z")
    }
}
