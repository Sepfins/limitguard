package io.github.sepfins.limitguard.infrastructure.policy.jsonlogic

import io.github.jamsesso.jsonlogic.JsonLogic
import io.github.sepfins.limitguard.domain.change.LimitChangePolicy
import io.github.sepfins.limitguard.domain.change.LimitChangePolicyDecision
import io.github.sepfins.limitguard.domain.change.LimitChangePolicyEngine
import io.github.sepfins.limitguard.domain.change.LimitChangePolicyEvaluationOutcome
import io.github.sepfins.limitguard.domain.common.CurrencyCode
import io.github.sepfins.limitguard.domain.common.Metadata
import io.github.sepfins.limitguard.domain.common.Money
import io.github.sepfins.limitguard.domain.policy.PolicyAction
import io.github.sepfins.limitguard.domain.policy.PolicyEvaluationContext
import io.github.sepfins.limitguard.domain.policy.PolicyEvaluationResult
import io.github.sepfins.limitguard.internal.hashing.CanonicalJson
import io.github.sepfins.limitguard.internal.policy.toPolicyEvaluationInput
import io.github.sepfins.limitguard.port.policy.PolicyEngine
import java.time.Duration
import java.time.Instant

/**
 * Evaluates JSON Logic conditions against an input document rooted at
 * `changeRequest`, `facts`, `phase`, and `evaluatedAt`. The request carries its
 * `requestedAmount` and `beforeAmount` as decimal strings, null when unlimited or absent.
 * An empty condition is treated as an unconditional match.
 * Monetary comparisons and all ordering use `decimal_eq`, `decimal_ne`, `decimal_gt`,
 * `decimal_gte`, `decimal_lt`, or `decimal_lte` with string operands.
 *
 * Supported actions are `SET_EFFECTIVE_AT` with either an `instant` or an `after` ISO-8601
 * duration counted from evaluation, and `CAP_REQUESTED_AMOUNT` with an `amount` and optional
 * `currency`. Action values may be literals or JSON Logic expressions evaluated against the same input.
 */
class JsonLogicPolicyEngine : PolicyEngine {
    private val jsonLogic = ExactDecimalJsonLogicOperations.installInto(JsonLogic())
    private val validator = JsonLogicPolicyValidator()

    override val engine: LimitChangePolicyEngine = LimitChangePolicyEngine.JSON_LOGIC

    override fun validate(policy: LimitChangePolicy) {
        validator.validate(policy)
    }

    override fun evaluate(
        policy: LimitChangePolicy,
        context: PolicyEvaluationContext,
    ): PolicyEvaluationResult {
        require(policy.engine == engine) {
            "policy engine ${policy.engine.name} cannot be evaluated by ${engine.name}"
        }
        require(policy.tenantId == context.tenantId) { "policy and evaluation context tenants must match" }
        require(policy.phase == context.phase) { "policy and evaluation context phases must match" }
        validate(policy)

        val input = context.toPolicyEvaluationInput().value
        val conditionResult = if (policy.conditionExpression.value.isEmpty()) {
            true
        } else {
            jsonLogic.apply(
                CanonicalJson.encodeObject(policy.conditionExpression.value),
                input,
            )
        }
        val matched = JsonLogic.truthy(conditionResult)
        val actions = if (matched) {
            policy.actionExpression.map { evaluateAction(it, input, context.evaluatedAt) }
        } else {
            emptyList()
        }
        return PolicyEvaluationResult(
            matched = matched,
            outcome = when {
                !matched -> LimitChangePolicyEvaluationOutcome.NOT_MATCHED
                policy.decision == LimitChangePolicyDecision.DENY ->
                    LimitChangePolicyEvaluationOutcome.DENIED
                policy.decision == LimitChangePolicyDecision.NO_OP && actions.isEmpty() ->
                    LimitChangePolicyEvaluationOutcome.MATCHED
                else -> LimitChangePolicyEvaluationOutcome.APPLIED
            },
            decision = policy.decision,
            actions = actions,
            actionResult = Metadata(mapOf("conditionResult" to conditionResult)),
            message = if (matched && policy.decision == LimitChangePolicyDecision.DENY) {
                policy.denialMessage
            } else {
                null
            },
        )
    }

    private fun evaluateAction(
        action: Map<String, Any?>,
        input: Map<String, Any?>,
        evaluatedAt: Instant,
    ): PolicyAction =
        when (val type = action.getValue("type") as String) {
            JsonLogicPolicyValidator.SET_EFFECTIVE_AT -> if (action.containsKey("after")) {
                val after = evaluateActionValue(action.getValue("after"), input) as? String
                    ?: throw IllegalArgumentException("SET_EFFECTIVE_AT after must evaluate to a string")
                val delay = runCatching { Duration.parse(after) }
                    .getOrElse { throw IllegalArgumentException("SET_EFFECTIVE_AT produced an invalid duration") }
                require(delay > Duration.ZERO) { "SET_EFFECTIVE_AT after must be a positive duration" }
                PolicyAction.SetEffectiveAt(evaluatedAt.plus(delay))
            } else {
                val instant = evaluateActionValue(action.getValue("instant"), input) as? String
                    ?: throw IllegalArgumentException("SET_EFFECTIVE_AT instant must evaluate to a string")
                PolicyAction.SetEffectiveAt(
                    runCatching { Instant.parse(instant) }
                        .getOrElse { throw IllegalArgumentException("SET_EFFECTIVE_AT produced an invalid instant") }
                )
            }
            JsonLogicPolicyValidator.CAP_REQUESTED_AMOUNT -> {
                val amount = evaluateActionValue(action.getValue("amount"), input) as? String
                    ?: throw IllegalArgumentException("CAP_REQUESTED_AMOUNT amount must evaluate to a decimal string")
                val currency = if (action.containsKey("currency")) {
                    evaluateActionValue(action.getValue("currency"), input) as? String
                        ?: throw IllegalArgumentException("CAP_REQUESTED_AMOUNT currency must evaluate to a string")
                } else {
                    input.requestCurrency()
                }
                PolicyAction.CapRequestedAmount(
                    Money.positive(amount, CurrencyCode.of(currency))
                )
            }
            else -> error("unsupported validated policy action: $type")
        }

    private fun evaluateActionValue(
        value: Any?,
        input: Map<String, Any?>,
    ): Any? =
        if (value is Map<*, *>) {
            @Suppress("UNCHECKED_CAST")
            jsonLogic.apply(CanonicalJson.encodeObject(value as Map<String, Any?>), input)
        } else {
            value
        }

    private fun Map<String, Any?>.requestCurrency(): String {
        val changeRequest = get("changeRequest") as? Map<*, *>
            ?: throw IllegalArgumentException("policy input is missing changeRequest")
        return changeRequest["currency"] as? String
            ?: throw IllegalArgumentException("policy input is missing changeRequest.currency")
    }
}
