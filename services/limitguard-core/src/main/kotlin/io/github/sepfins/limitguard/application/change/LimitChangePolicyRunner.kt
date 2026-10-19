package io.github.sepfins.limitguard.application.change

import io.github.sepfins.limitguard.api.errors.ValidationException
import io.github.sepfins.limitguard.domain.change.LimitChangePolicy
import io.github.sepfins.limitguard.domain.change.LimitChangePolicyCapabilities
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
import io.github.sepfins.limitguard.internal.json.JsonSnapshots
import io.github.sepfins.limitguard.port.ids.IdGenerator
import io.github.sepfins.limitguard.port.persistence.LimitChangePolicyEvaluationRepository
import io.github.sepfins.limitguard.port.persistence.LimitChangePolicyRepository
import io.github.sepfins.limitguard.port.persistence.LimitRuleChangeEventRepository
import java.time.Instant
import java.util.Collections

internal class LimitChangePolicyRunner(
    private val policyRepository: LimitChangePolicyRepository,
    private val evaluationRepository: LimitChangePolicyEvaluationRepository,
    private val changeEventRepository: LimitRuleChangeEventRepository,
    private val engineRegistry: PolicyEngineRegistry,
    private val changeEventFactory: LimitChangeEventFactory,
    private val idGenerator: IdGenerator,
    private val actionExecutor: LimitChangePolicyActionExecutor = LimitChangePolicyActionExecutor(),
) {
    fun authorize(
        request: LimitRuleChangeRequest,
        at: Instant,
    ): LimitChangeAuthorization {
        val policies = policyRepository
            .findActiveForConfiguration(request.tenantId, LimitChangePolicyPhase.AUTHORIZATION, request.configurationId, at)
            .map { it.copy(actionExpression = JsonSnapshots.objectList(it.actionExpression, "policy.actionExpression")) }
            .sortedWith(compareBy<LimitChangePolicy> { it.priority.value }.thenBy { it.code.value })
        var currentRequest = request

        for (policy in policies) {
            val context = PolicyEvaluationContext(
                tenantId = currentRequest.tenantId,
                phase = LimitChangePolicyPhase.AUTHORIZATION,
                changeRequest = currentRequest,
                facts = currentRequest.policyContext,
                evaluatedAt = at,
            )
            val inputContext = context.toPolicyEvaluationInput()
            val beforeActions = currentRequest
            val run = evaluate(policy, context)
            val result = run.result
            currentRequest = run.request
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
                changeEventFactory.policyEvaluated(beforeActions, currentRequest, evaluation, at),
            )

            if (evaluation.outcome == LimitChangePolicyEvaluationOutcome.ERROR) {
                return LimitChangeAuthorization.Failed(policy, evaluation, currentRequest)
            }
            if (!evaluation.matched) continue
            when (evaluation.decision) {
                LimitChangePolicyDecision.ALLOW ->
                    return LimitChangeAuthorization.Allowed(policy, evaluation, currentRequest)
                LimitChangePolicyDecision.DENY ->
                    return LimitChangeAuthorization.Denied(policy, evaluation, currentRequest)
                LimitChangePolicyDecision.REQUIRE_APPROVAL ->
                    return LimitChangeAuthorization.ManualApprovalRequired(currentRequest, policy, evaluation)
                LimitChangePolicyDecision.NO_OP -> Unit
                else -> error("unsupported authorization decision was not normalized")
            }
        }

        // Absence of a matching policy must never become implicit approval.
        return LimitChangeAuthorization.ManualApprovalRequired(currentRequest)
    }

    private fun evaluate(
        policy: LimitChangePolicy,
        context: PolicyEvaluationContext,
    ): PolicyRun {
        val result = try {
            require(policy.decision in LimitChangePolicyCapabilities.authorizationDecisions) {
                "Policy decision ${policy.decision.name} is not supported during AUTHORIZATION"
            }
            val engine = engineRegistry.get(policy.engine)
            engine.validate(policy)
            engine.evaluate(policy, context).let { result ->
                result.copy(actions = Collections.unmodifiableList(ArrayList(result.actions)))
            }
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            throw exception
        } catch (exception: ValidationException) {
            throw exception
        } catch (exception: Exception) {
            val detail = exception.message ?: exception::class.simpleName ?: "unknown error"
            return PolicyRun(
                result = PolicyEvaluationResult(
                    matched = false,
                    outcome = LimitChangePolicyEvaluationOutcome.ERROR,
                    decision = policy.decision,
                    message = "Policy ${policy.code.value} evaluation failed: $detail",
                ),
                request = context.changeRequest,
            )
        }

        val inconsistentOutcome =
            (result.matched && result.outcome == LimitChangePolicyEvaluationOutcome.NOT_MATCHED) ||
                (!result.matched && result.outcome in MATCHED_OUTCOMES)
        if (inconsistentOutcome) {
            return PolicyRun(
                result = result.copy(
                    matched = false,
                    outcome = LimitChangePolicyEvaluationOutcome.ERROR,
                    message = "Policy ${policy.code.value} returned an inconsistent evaluation outcome",
                ),
                request = context.changeRequest,
            )
        }
        if (!result.matched && result.actions.isNotEmpty()) {
            return PolicyRun(
                result = result.copy(
                    outcome = LimitChangePolicyEvaluationOutcome.ERROR,
                    message = "Policy ${policy.code.value} returned actions without matching",
                    actions = emptyList(),
                ),
                request = context.changeRequest,
            )
        }
        if (result.matched && policy.actionExpression.isNotEmpty() && result.actions.isEmpty()) {
            return PolicyRun(
                result = result.copy(
                    matched = false,
                    outcome = LimitChangePolicyEvaluationOutcome.ERROR,
                    message = "Policy ${policy.code.value} did not produce its configured actions",
                ),
                request = context.changeRequest,
            )
        }
        if (result.matched && result.decision !in LimitChangePolicyCapabilities.authorizationDecisions) {
            return PolicyRun(
                result = result.copy(
                    matched = false,
                    outcome = LimitChangePolicyEvaluationOutcome.ERROR,
                    message = "Policy decision ${result.decision.name} is not supported during AUTHORIZATION",
                    actions = emptyList(),
                ),
                request = context.changeRequest,
            )
        }

        return try {
            val execution = actionExecutor.execute(context.changeRequest, result.actions, context.evaluatedAt)
            val actionResult = if (execution.results.isEmpty()) {
                result.actionResult
            } else {
                Metadata(result.actionResult.value + ("actions" to execution.results))
            }
            PolicyRun(
                result = result.copy(actionResult = actionResult),
                request = execution.request,
            )
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            throw exception
        } catch (exception: Exception) {
            val detail = exception.message ?: exception::class.simpleName ?: "unknown error"
            PolicyRun(
                result = result.copy(
                    matched = false,
                    outcome = LimitChangePolicyEvaluationOutcome.ERROR,
                    actions = emptyList(),
                    message = "Policy ${policy.code.value} action execution failed: $detail",
                ),
                request = context.changeRequest,
            )
        }
    }

    private companion object {
        val MATCHED_OUTCOMES = setOf(
            LimitChangePolicyEvaluationOutcome.MATCHED,
            LimitChangePolicyEvaluationOutcome.APPLIED,
            LimitChangePolicyEvaluationOutcome.DENIED,
        )
    }
}

internal sealed interface LimitChangeAuthorization {
    val request: LimitRuleChangeRequest

    data class Allowed(
        val policy: LimitChangePolicy,
        val evaluation: LimitChangePolicyEvaluation,
        override val request: LimitRuleChangeRequest,
    ) : LimitChangeAuthorization

    data class Denied(
        val policy: LimitChangePolicy,
        val evaluation: LimitChangePolicyEvaluation,
        override val request: LimitRuleChangeRequest,
    ) : LimitChangeAuthorization

    data class ManualApprovalRequired(
        override val request: LimitRuleChangeRequest,
        val policy: LimitChangePolicy? = null,
        val evaluation: LimitChangePolicyEvaluation? = null,
    ) : LimitChangeAuthorization

    data class Failed(
        val policy: LimitChangePolicy,
        val evaluation: LimitChangePolicyEvaluation,
        override val request: LimitRuleChangeRequest,
    ) : LimitChangeAuthorization
}

private data class PolicyRun(
    val result: PolicyEvaluationResult,
    val request: LimitRuleChangeRequest,
)

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
