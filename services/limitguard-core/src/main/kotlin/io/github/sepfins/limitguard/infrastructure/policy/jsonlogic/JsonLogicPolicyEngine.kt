package io.github.sepfins.limitguard.infrastructure.policy.jsonlogic

import io.github.jamsesso.jsonlogic.JsonLogic
import io.github.sepfins.limitguard.domain.change.LimitChangePolicy
import io.github.sepfins.limitguard.domain.change.LimitChangePolicyDecision
import io.github.sepfins.limitguard.domain.change.LimitChangePolicyEngine
import io.github.sepfins.limitguard.domain.change.LimitChangePolicyEvaluationOutcome
import io.github.sepfins.limitguard.domain.common.Metadata
import io.github.sepfins.limitguard.domain.policy.PolicyEvaluationContext
import io.github.sepfins.limitguard.domain.policy.PolicyEvaluationResult
import io.github.sepfins.limitguard.internal.hashing.CanonicalJson
import io.github.sepfins.limitguard.internal.policy.toPolicyEvaluationInput
import io.github.sepfins.limitguard.port.policy.PolicyEngine

/**
 * Evaluates JSON Logic conditions against an input document rooted at
 * `changeRequest`, `currentRule`, `facts`, `phase`, and `evaluatedAt`.
 * An empty condition is treated as an unconditional match.
 */
class JsonLogicPolicyEngine : PolicyEngine {
    private val jsonLogic = JsonLogic()

    override val engine: LimitChangePolicyEngine = LimitChangePolicyEngine.JSON_LOGIC

    override fun evaluate(
        policy: LimitChangePolicy,
        context: PolicyEvaluationContext,
    ): PolicyEvaluationResult {
        require(policy.engine == engine) {
            "policy engine ${policy.engine.name} cannot be evaluated by ${engine.name}"
        }
        require(policy.tenantId == context.tenantId) { "policy and evaluation context tenants must match" }
        require(policy.phase == context.phase) { "policy and evaluation context phases must match" }

        val conditionResult = if (policy.conditionExpression.value.isEmpty()) {
            true
        } else {
            jsonLogic.apply(
                CanonicalJson.encodeObject(policy.conditionExpression.value),
                context.toPolicyEvaluationInput().value,
            )
        }
        val matched = JsonLogic.truthy(conditionResult)
        return PolicyEvaluationResult(
            matched = matched,
            outcome = when {
                !matched -> LimitChangePolicyEvaluationOutcome.NOT_MATCHED
                policy.decision == LimitChangePolicyDecision.DENY ->
                    LimitChangePolicyEvaluationOutcome.DENIED
                policy.decision == LimitChangePolicyDecision.NO_OP ->
                    LimitChangePolicyEvaluationOutcome.MATCHED
                else -> LimitChangePolicyEvaluationOutcome.APPLIED
            },
            decision = policy.decision,
            actionResult = Metadata(mapOf("conditionResult" to conditionResult)),
            message = if (matched && policy.decision == LimitChangePolicyDecision.DENY) {
                policy.denialMessage
            } else {
                null
            },
        )
    }
}
