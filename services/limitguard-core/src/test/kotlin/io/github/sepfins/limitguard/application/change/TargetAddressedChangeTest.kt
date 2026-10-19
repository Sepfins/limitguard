package io.github.sepfins.limitguard.application.change

import io.github.sepfins.limitguard.api.AuthorizationDecision
import io.github.sepfins.limitguard.api.LimitAmount
import io.github.sepfins.limitguard.api.LimitKind
import io.github.sepfins.limitguard.api.LimitRuleChangeStatus
import io.github.sepfins.limitguard.api.LimitRuleChangeType
import io.github.sepfins.limitguard.api.LimitRuleRevisionEventType
import io.github.sepfins.limitguard.api.commands.LimitRuleChangeOperation
import io.github.sepfins.limitguard.api.errors.ConflictException
import io.github.sepfins.limitguard.api.errors.NotFoundException
import io.github.sepfins.limitguard.api.queries.GetLimitConfigurationQuery
import io.github.sepfins.limitguard.api.queries.GetLimitRuleQuery
import io.github.sepfins.limitguard.api.results.LimitRuleResult
import io.github.sepfins.limitguard.support.FixedClockProvider
import io.github.sepfins.limitguard.support.OperationSubtreeScenario
import io.github.sepfins.limitguard.support.PostgresFacade
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

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
        assertEquals(
            LimitRuleResult(ruleId, s.tenant, s.owner, configuration, null, bounded("300"), true, 2, emptyMap(), NOW, NOW),
            rule(s, ruleId),
        )
        assertEquals(LimitKind.PERIODIC, s.facade.limitInquiryService
            .getLimitConfiguration(GetLimitConfigurationQuery(s.tenant, configuration)).limitKind)
        val details = s.details(lowered.id)
        assertEquals(bounded("500"), details.request.beforeAmount)
        assertEquals(bounded("300"), details.request.requestedAmount)
        assertEquals(listOf("REQUESTED", "APPROVED", "APPLIED"), details.events.map { it.eventType.name })
        val revision = details.revisions.single()
        assertEquals(LimitRuleRevisionEventType.UPDATED, revision.eventType)
        assertEquals(2, revision.revisionNumber)
        assertEquals(bounded("500"), revision.beforeAmount)
        assertEquals(bounded("300"), revision.afterAmount)
        assertEquals(BigDecimal("40.0000"), s.usage(ruleId).reservedAmount)
        assertEquals(AuthorizationDecision.DENIED, s.authorize(transfer, "261").decision)

        s.approve(s.request(configuration, LimitRuleChangeOperation.Deactivate, expectedVersion = 2).id)
        s.approve(s.request(configuration, setAmount("400"), expectedVersion = 3).id)

        val inactive = rule(s, ruleId)
        assertEquals(false, inactive.isActive)
        assertEquals(bounded("400"), inactive.amount)
        assertEquals(4, inactive.version)
    }

    @Test
    fun `deactivate reactivate and delete address the rule by target and set amount grants the deleted rule again`() {
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
        assertFailsWith<NotFoundException> { s.request(configuration, LimitRuleChangeOperation.Reactivate) }
        assertFailsWith<ConflictException> { s.request(configuration, setAmount("200"), expectedVersion = 4) }

        // One rule per target: the deleted row is granted again, so its usage carries over.
        val recreated = s.approve(s.request(configuration, setAmount("200")).id)

        assertEquals(LimitRuleChangeType.CREATE, recreated.changeType)
        assertEquals(ruleId, recreated.limitRuleId)
        assertEquals(true, rule(s, ruleId).isActive)
        assertEquals(5, rule(s, ruleId).version)
        val revision = s.details(recreated.id).revisions.single()
        assertEquals(LimitRuleRevisionEventType.CREATED, revision.eventType)
        assertEquals(null, revision.beforeAmount)
        assertEquals(bounded("200"), revision.afterAmount)
        assertEquals(AuthorizationDecision.DENIED, s.authorize(transfer, "250").decision)
    }

    private fun setAmount(amount: String) = LimitRuleChangeOperation.SetAmount(LimitAmount.Bounded(BigDecimal(amount)))

    private fun bounded(amount: String) = LimitAmount.Bounded(BigDecimal(amount).setScale(4))

    private fun rule(s: OperationSubtreeScenario, id: UUID) =
        s.facade.limitInquiryService.getLimitRule(GetLimitRuleQuery(s.tenant, id))

    private fun scenario() = OperationSubtreeScenario(PostgresFacade.create(FixedClockProvider(NOW), emptyList()), NOW)

    private companion object {
        val NOW: Instant = Instant.parse("2026-06-26T12:00:00Z")
    }
}
