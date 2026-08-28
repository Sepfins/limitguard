package io.github.sepfins.limitguard.application.change

import io.github.sepfins.limitguard.domain.change.LimitChangePolicy
import io.github.sepfins.limitguard.domain.change.LimitChangePolicyDecision
import io.github.sepfins.limitguard.domain.change.LimitChangePolicyEvaluation
import io.github.sepfins.limitguard.domain.change.LimitChangePolicyEvaluationOutcome
import io.github.sepfins.limitguard.domain.change.LimitChangePolicyPhase
import io.github.sepfins.limitguard.domain.change.LimitRuleChangeRequest
import io.github.sepfins.limitguard.domain.common.LimitChangePolicyEvaluationId
import io.github.sepfins.limitguard.domain.common.Metadata
import io.github.sepfins.limitguard.domain.policy.PolicyEvaluationContext
import io.github.sepfins.limitguard.domain.policy.PolicyEvaluationResult
import io.github.sepfins.limitguard.internal.policy.toPolicyEvaluationInput
import io.github.sepfins.limitguard.port.ids.IdGenerator
import io.github.sepfins.limitguard.port.persistence.LimitChangePolicyEvaluationRepository
import io.github.sepfins.limitguard.port.persistence.LimitChangePolicyRepository
import io.github.sepfins.limitguard.port.persistence.LimitRuleChangeEventRepository
import java.time.Instant

internal class LimitChangePolicyRunner(
    private val policyRepository: LimitChangePolicyRepository,
    private val evaluationRepository: LimitChangePolicyEvaluationRepository,
    private val changeEventRepository: LimitRuleChangeEventRepository,
    private val engineRegistry: PolicyEngineRegistry,
    private val changeEventFactory: LimitChangeEventFactory,
    private val idGenerator: IdGenerator,
) {
    fun authorize(
        request: LimitRuleChangeRequest,
        at: Instant,
    ): LimitChangeAuthorization {
        val context = PolicyEvaluationContext(
            tenantId = request.tenantId,
            phase = LimitChangePolicyPhase.AUTHORIZATION,
            changeRequest = request,
            currentRule = request.beforeRule,
            facts = request.policyContext,
            evaluatedAt = at,
        )
        val inputContext = context.toPolicyEvaluationInput()
        val policies = policyRepository
            .findActiveByPhase(request.tenantId, context.phase, at)
            .sortedWith(compareBy<LimitChangePolicy> { it.priority.value }.thenBy { it.code.value })

        for (policy in policies) {
            val result = evaluate(policy, context)
            val evaluation = evaluationRepository.insert(
                request.tenantId,
                LimitChangePolicyEvaluation(
                    id = LimitChangePolicyEvaluationId.of(idGenerator.newId()),
                    tenantId = request.tenantId,
                    changeRequestId = request.id,
                    policyId = policy.id,
                    phase = context.phase,
                    outcome = result.outcome,
                    decision = result.decision,
                    matched = result.matched,
                    policySnapshot = policy.toSnapshot(),
                    inputContext = inputContext,
                    actionResult = result.actionResult,
                    message = result.message,
                    evaluatedAt = at,
                ),
            )
            changeEventRepository.insert(
                request.tenantId,
                changeEventFactory.policyEvaluated(request, evaluation, at),
            )

            if (evaluation.outcome == LimitChangePolicyEvaluationOutcome.ERROR) {
                return LimitChangeAuthorization.Failed(policy, evaluation)
            }
            if (!evaluation.matched) continue
            when (evaluation.decision) {
                LimitChangePolicyDecision.ALLOW ->
                    return LimitChangeAuthorization.Allowed(policy, evaluation)
                LimitChangePolicyDecision.DENY ->
                    return LimitChangeAuthorization.Denied(policy, evaluation)
                LimitChangePolicyDecision.REQUIRE_APPROVAL ->
                    return LimitChangeAuthorization.ManualApprovalRequired(policy, evaluation)
                LimitChangePolicyDecision.NO_OP -> Unit
                else -> error("unsupported authorization decision was not normalized")
            }
        }

        // Absence of a matching policy must never become implicit approval.
        return LimitChangeAuthorization.ManualApprovalRequired()
    }

    private fun evaluate(
        policy: LimitChangePolicy,
        context: PolicyEvaluationContext,
    ): PolicyEvaluationResult {
        val result = try {
            engineRegistry.get(policy.engine).evaluate(policy, context)
        } catch (exception: Exception) {
            val detail = exception.message ?: exception::class.simpleName ?: "unknown error"
            return PolicyEvaluationResult(
                matched = false,
                outcome = LimitChangePolicyEvaluationOutcome.ERROR,
                decision = policy.decision,
                message = "Policy ${policy.code.value} evaluation failed: $detail",
            )
        }

        val inconsistentOutcome =
            (result.matched && result.outcome == LimitChangePolicyEvaluationOutcome.NOT_MATCHED) ||
                (!result.matched && result.outcome in MATCHED_OUTCOMES)
        if (inconsistentOutcome) {
            return result.copy(
                matched = false,
                outcome = LimitChangePolicyEvaluationOutcome.ERROR,
                message = "Policy ${policy.code.value} returned an inconsistent evaluation outcome",
            )
        }
        if (result.matched && result.decision !in AUTHORIZATION_DECISIONS) {
            return result.copy(
                matched = false,
                outcome = LimitChangePolicyEvaluationOutcome.ERROR,
                message = "Policy decision ${result.decision.name} is not supported during AUTHORIZATION",
            )
        }
        return result
    }

    private companion object {
        val AUTHORIZATION_DECISIONS = setOf(
            LimitChangePolicyDecision.ALLOW,
            LimitChangePolicyDecision.DENY,
            LimitChangePolicyDecision.REQUIRE_APPROVAL,
            LimitChangePolicyDecision.NO_OP,
        )
        val MATCHED_OUTCOMES = setOf(
            LimitChangePolicyEvaluationOutcome.MATCHED,
            LimitChangePolicyEvaluationOutcome.APPLIED,
            LimitChangePolicyEvaluationOutcome.DENIED,
        )
    }
}

internal sealed interface LimitChangeAuthorization {
    data class Allowed(
        val policy: LimitChangePolicy,
        val evaluation: LimitChangePolicyEvaluation,
    ) : LimitChangeAuthorization

    data class Denied(
        val policy: LimitChangePolicy,
        val evaluation: LimitChangePolicyEvaluation,
    ) : LimitChangeAuthorization

    data class ManualApprovalRequired(
        val policy: LimitChangePolicy? = null,
        val evaluation: LimitChangePolicyEvaluation? = null,
    ) : LimitChangeAuthorization

    data class Failed(
        val policy: LimitChangePolicy,
        val evaluation: LimitChangePolicyEvaluation,
    ) : LimitChangeAuthorization
}

private fun LimitChangePolicy.toSnapshot(): Metadata =
    Metadata(
        mapOf(
            "id" to id.value.toString(),
            "tenantId" to tenantId.value.toString(),
            "code" to code.value,
            "name" to name.value,
            "description" to description,
            "phase" to phase.name,
            "engine" to engine.name,
            "decision" to decision.name,
            "limitConfigurationId" to limitConfigurationId?.value?.toString(),
            "priority" to priority.value,
            "isActive" to isActive,
            "appliesTo" to appliesTo.value,
            "conditionExpression" to conditionExpression.value,
            "actionExpression" to actionExpression,
            "denialCode" to denialCode,
            "denialMessage" to denialMessage,
            "startsAt" to effectiveWindow.startsAt.toString(),
            "endsAt" to effectiveWindow.endsAt?.toString(),
            "metadata" to metadata.value,
            "createdAt" to createdAt?.toString(),
            "updatedAt" to updatedAt?.toString(),
        )
    )
