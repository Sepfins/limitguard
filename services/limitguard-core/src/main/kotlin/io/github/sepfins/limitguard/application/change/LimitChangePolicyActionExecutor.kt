package io.github.sepfins.limitguard.application.change

import io.github.sepfins.limitguard.domain.change.LimitRuleChangeRequest
import io.github.sepfins.limitguard.domain.common.CurrencyCode
import io.github.sepfins.limitguard.domain.common.Metadata
import io.github.sepfins.limitguard.domain.common.Money
import io.github.sepfins.limitguard.domain.policy.PolicyAction
import java.time.Instant

internal class LimitChangePolicyActionExecutor {
    fun execute(
        request: LimitRuleChangeRequest,
        actions: List<PolicyAction>,
        at: Instant,
    ): PolicyActionExecution {
        var current = request
        val results = actions.map { action ->
            val execution = when (action) {
                is PolicyAction.SetEffectiveAt -> setEffectiveAt(current, action, at)
                is PolicyAction.CapRequestedAmount -> capRequestedAmount(current, action, at)
            }
            current = execution.request
            execution.result
        }
        return PolicyActionExecution(current, results)
    }

    private fun setEffectiveAt(
        request: LimitRuleChangeRequest,
        action: PolicyAction.SetEffectiveAt,
        at: Instant,
    ): SingleActionExecution {
        require(action.effectiveAt > at) { "SET_EFFECTIVE_AT must schedule the request in the future" }
        val ruleEndsAt = request.requestedRule.value["endsAt"]
            ?.let { Instant.parse(it as? String ?: error("requested rule endsAt must be a string")) }
        require(ruleEndsAt == null || ruleEndsAt > action.effectiveAt) {
            "SET_EFFECTIVE_AT must be before the requested rule end"
        }

        val before = request.effectiveAt
        val changed = before != action.effectiveAt
        val after = if (changed) {
            request.copy(effectiveAt = action.effectiveAt, updatedAt = at)
        } else {
            request
        }
        return SingleActionExecution(
            request = after,
            result = mapOf(
                "type" to "SET_EFFECTIVE_AT",
                "before" to before?.toString(),
                "after" to action.effectiveAt.toString(),
                "changed" to changed,
            ),
        )
    }

    private fun capRequestedAmount(
        request: LimitRuleChangeRequest,
        action: PolicyAction.CapRequestedAmount,
        at: Instant,
    ): SingleActionExecution {
        val requestedRule = request.requestedRule.value
        val currency = CurrencyCode.of(
            requestedRule["currency"] as? String
                ?: throw IllegalArgumentException("requested rule currency must be a string")
        )
        require(action.maximumAmount.currency == currency) {
            "CAP_REQUESTED_AMOUNT currency must match the requested rule currency"
        }
        val before = Money.positive(
            requestedRule["amount"] as? String
                ?: throw IllegalArgumentException("requested rule amount must be a decimal string"),
            currency,
        )
        val changed = before > action.maximumAmount
        val afterAmount = if (changed) action.maximumAmount else before
        val after = if (changed) {
            request.copy(
                requestedRule = Metadata(requestedRule + ("amount" to afterAmount.amount.toString())),
                updatedAt = at,
            )
        } else {
            request
        }
        return SingleActionExecution(
            request = after,
            result = mapOf(
                "type" to "CAP_REQUESTED_AMOUNT",
                "maximum" to action.maximumAmount.amount.toString(),
                "currency" to currency.value,
                "before" to before.amount.toString(),
                "after" to afterAmount.amount.toString(),
                "changed" to changed,
            ),
        )
    }

    private data class SingleActionExecution(
        val request: LimitRuleChangeRequest,
        val result: Map<String, Any?>,
    )
}

internal data class PolicyActionExecution(
    val request: LimitRuleChangeRequest,
    val results: List<Map<String, Any?>>,
)
