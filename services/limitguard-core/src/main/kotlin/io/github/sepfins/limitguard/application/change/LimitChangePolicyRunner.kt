package io.github.sepfins.limitguard.application.change

import io.github.sepfins.limitguard.domain.change.ChangeActor
import io.github.sepfins.limitguard.domain.change.LimitChangePolicy
import io.github.sepfins.limitguard.domain.change.LimitChangePolicyDecision
import io.github.sepfins.limitguard.domain.change.LimitChangePolicyEvaluation
import io.github.sepfins.limitguard.domain.change.LimitChangePolicyEvaluationOutcome
import io.github.sepfins.limitguard.domain.change.LimitChangePolicyPhase
import io.github.sepfins.limitguard.domain.change.LimitRuleChangeRequest
import io.github.sepfins.limitguard.domain.change.RequestActorContext
import io.github.sepfins.limitguard.domain.common.LimitChangePolicyEvaluationId
import io.github.sepfins.limitguard.domain.common.Metadata
import io.github.sepfins.limitguard.domain.policy.PolicyEvaluationContext
import io.github.sepfins.limitguard.domain.policy.PolicyEvaluationResult
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
        val inputContext = context.toSnapshot()
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
        } catch (exception: RuntimeException) {
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

private fun PolicyEvaluationContext.toSnapshot(): Metadata =
    Metadata(
        mapOf(
            "phase" to phase.name,
            "evaluatedAt" to evaluatedAt.toString(),
            "changeRequest" to changeRequest.toPolicyInput(),
            "currentRule" to currentRule?.value,
            "facts" to facts.value,
        )
    )

private fun LimitRuleChangeRequest.toPolicyInput(): Map<String, Any?> =
    mapOf(
        "id" to id.value.toString(),
        "tenantId" to tenantId.value.toString(),
        "changeType" to changeType.name,
        "status" to status.name,
        "limitRuleId" to limitRuleId?.value?.toString(),
        "requestedBy" to requestedBy.toPolicyInput(),
        "requestActorContext" to requestActorContext.toPolicyInput(),
        "requesterPartyProfileId" to requesterPartyProfileId?.value?.toString(),
        "authorityDelegationId" to authorityDelegationId?.value?.toString(),
        "targetOwnerPartyProfileId" to targetOwnerPartyProfileId.value.toString(),
        "targetMandateId" to targetMandateId?.value?.toString(),
        "targetScope" to targetScope.value,
        "targetScopeHash" to targetScopeHash.value,
        "beforeRule" to beforeRule?.value,
        "requestedRule" to requestedRule.value,
        "effectiveAt" to effectiveAt?.toString(),
        "expiresAt" to expiresAt?.toString(),
        "policyContext" to policyContext.value,
        "idempotencyKey" to idempotencyKey?.value,
        "metadata" to metadata.value,
    )

private fun ChangeActor.toPolicyInput(): Map<String, Any?> =
    when (this) {
        ChangeActor.System -> mapOf("actorType" to "SYSTEM")
        is ChangeActor.PartyProfileActor -> mapOf(
            "actorType" to "PARTY_PROFILE",
            "partyProfileId" to partyProfileId.value.toString(),
        )
        is ChangeActor.BankWorkerActor -> mapOf(
            "actorType" to "BANK_WORKER",
            "bankWorkerId" to bankWorkerId.value.toString(),
        )
    }

private fun RequestActorContext.toPolicyInput(): Map<String, Any?> =
    when (this) {
        RequestActorContext.System -> mapOf("actorType" to "SYSTEM")
        is RequestActorContext.PartyProfile -> mapOf(
            "actorType" to "PARTY_PROFILE",
            "requesterPartyProfileId" to requesterPartyProfileId.value.toString(),
            "requesterChannelId" to requesterChannelId.value.toString(),
        )
        is RequestActorContext.BankWorker -> mapOf(
            "actorType" to "BANK_WORKER",
            "bankWorkerId" to bankWorkerId.value.toString(),
            "requesterChannelId" to requesterChannelId.value.toString(),
        )
        else -> mapOf("actorType" to (this::class.simpleName ?: "UNKNOWN"))
    }
