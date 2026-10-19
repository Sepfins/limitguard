package io.github.sepfins.limitguard.application.change

import io.github.sepfins.limitguard.api.LimitAmount
import io.github.sepfins.limitguard.api.LimitChangeActorType
import io.github.sepfins.limitguard.api.LimitChangePolicyDecision
import io.github.sepfins.limitguard.api.LimitChangePolicyPhase
import io.github.sepfins.limitguard.api.LimitRuleChangeStatus
import io.github.sepfins.limitguard.api.TrustedChangeActorContext
import io.github.sepfins.limitguard.api.commands.CancelLimitRuleChangeCommand
import io.github.sepfins.limitguard.api.commands.CreateLimitChangePolicyCommand
import io.github.sepfins.limitguard.api.commands.LimitChangeActorCommand
import io.github.sepfins.limitguard.api.commands.LimitRuleChangeOperation
import io.github.sepfins.limitguard.api.commands.RejectLimitRuleChangeCommand
import io.github.sepfins.limitguard.support.FixedClockProvider
import io.github.sepfins.limitguard.support.OperationSubtreeScenario
import io.github.sepfins.limitguard.support.PostgresFacade
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SubmissionPolicyTest {
    @Test
    fun `policies decide once at submission by configuration binding priority and exact amount`() {
        val s = scenario()
        val transfer = s.operation("TRANSFER", leaf = true)
        val configuration = s.configuration(transfer)
        val otherConfiguration = s.configuration(transfer)
        policy(s, "OTHER_ONLY", LimitChangePolicyDecision.DENY, priority = 1, configuration = otherConfiguration,
            denialMessage = "Not for this configuration")
        policy(s, "TOO_HIGH", LimitChangePolicyDecision.DENY, priority = 10, denialMessage = "Above 900",
            condition = mapOf("decimal_gt" to listOf(amount(), "900")))
        policy(s, "SMALL", LimitChangePolicyDecision.ALLOW, priority = 20,
            condition = mapOf("decimal_lte" to listOf(amount(), "300")))

        val denied = s.request(configuration, setAmount("950"))
        assertEquals(LimitRuleChangeStatus.REJECTED, denied.status)
        assertEquals("Above 900", denied.decisionReason)
        assertEquals("Not for this configuration", s.request(otherConfiguration, setAmount("100")).decisionReason)

        val manual = s.request(configuration, setAmount("300.0001"))
        assertEquals(LimitRuleChangeStatus.REQUESTED, manual.status)
        val cancelled = s.facade.limitChangeService.cancelLimitRuleChange(CancelLimitRuleChangeCommand(s.tenant, manual.id, SYSTEM),
            TrustedChangeActorContext.InternalSystem)
        assertEquals(LimitRuleChangeStatus.CANCELLED, cancelled.status)

        val automatic = s.request(configuration, setAmount("300"))
        assertEquals(LimitRuleChangeStatus.APPLIED, automatic.status)

        val reviewed = s.request(configuration, setAmount("500"), expectedVersion = 1)
        val rejected = s.facade.limitChangeService.rejectLimitRuleChange(
            RejectLimitRuleChangeCommand(s.tenant, reviewed.id, SYSTEM, "Ask for less"), TrustedChangeActorContext.InternalSystem,
        )
        assertEquals(LimitRuleChangeStatus.REJECTED, rejected.status)
        assertEquals(LimitRuleChangeStatus.APPLIED, s.approve(s.request(configuration, setAmount("500"), expectedVersion = 1).id).status)
    }

    @Test
    fun `cap and schedule actions shape the request before approval`() {
        val s = scenario()
        val transfer = s.operation("TRANSFER", leaf = true)
        val configuration = s.configuration(transfer)
        val due = NOW.plusSeconds(60)
        policy(s, "CAP_AND_DELAY", LimitChangePolicyDecision.REQUIRE_APPROVAL, actions = listOf(
            mapOf("type" to "CAP_REQUESTED_AMOUNT", "amount" to mapOf("var" to "facts.cap")),
            mapOf("type" to "SET_EFFECTIVE_AT", "instant" to due.toString()),
        ))

        val request = s.facade.limitChangeService.requestLimitRuleChange(
            s.changeCommand(configuration, setAmount("800")).copy(policyContext = mapOf("cap" to "250.50")),
        )

        assertEquals(LimitRuleChangeStatus.REQUESTED, request.status)
        assertEquals(due, request.effectiveAt)
        assertEquals(LimitRuleChangeStatus.PENDING_EFFECTIVE_DATE, s.approve(request.id).status)
        assertEquals(LimitAmount.Bounded(BigDecimal("250.5000")), s.details(request.id).request.requestedAmount)
    }

    @Test
    fun `submission fails closed on a missing engine or an expression or action that cannot be evaluated`() {
        for (case in listOf("missing engine", "expression", "action")) {
            val source = PostgresFacade.database()
            val s = OperationSubtreeScenario(PostgresFacade.create(FixedClockProvider(NOW), source = source), NOW)
            policy(s, "CHECK", LimitChangePolicyDecision.ALLOW,
                condition = if (case == "expression") mapOf("decimal_gt" to listOf(mapOf("var" to "facts.limit"), "1")) else emptyMap(),
                actions = if (case == "action") listOf(mapOf("type" to "CAP_REQUESTED_AMOUNT", "amount" to mapOf("var" to "facts.cap"))) else emptyList(),
            )
            val submitting = if (case == "missing engine") PostgresFacade.create(FixedClockProvider(NOW), emptyList(), source) else s.facade
            val configuration = s.configuration(s.operation("TRANSFER", leaf = true))

            val failed = submitting.limitChangeService.requestLimitRuleChange(
                s.changeCommand(configuration, setAmount("100")).copy(policyContext = mapOf("limit" to "not a decimal")),
            )

            assertEquals(LimitRuleChangeStatus.FAILED, failed.status, case)
            assertNull(failed.limitRuleId, case)
        }
    }

    private fun policy(
        s: OperationSubtreeScenario,
        code: String,
        decision: LimitChangePolicyDecision,
        priority: Int = 100,
        configuration: UUID? = null,
        condition: Map<String, Any?> = emptyMap(),
        actions: List<Map<String, Any?>> = emptyList(),
        denialMessage: String? = null,
    ) = s.facade.tenantCatalogService.createLimitChangePolicy(CreateLimitChangePolicyCommand(
        tenantId = s.tenant, code = code, name = code, phase = LimitChangePolicyPhase.AUTHORIZATION,
        decision = decision, limitConfigurationId = configuration, priority = priority,
        conditionExpression = condition, actionExpression = actions, denialMessage = denialMessage,
    ))

    private fun amount() = mapOf("var" to "changeRequest.requestedAmount")

    private fun setAmount(amount: String) = LimitRuleChangeOperation.SetAmount(LimitAmount.Bounded(BigDecimal(amount)))

    private fun scenario() = OperationSubtreeScenario(PostgresFacade.create(FixedClockProvider(NOW)), NOW)

    private companion object {
        val NOW: Instant = Instant.parse("2026-06-26T12:00:00Z")
        val SYSTEM = LimitChangeActorCommand(LimitChangeActorType.SYSTEM)
    }
}
