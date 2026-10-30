package io.github.sepfins.limitguard.application.change

import io.github.sepfins.limitguard.application.boundedMoney
import io.github.sepfins.limitguard.domain.change.LimitRuleChangeRequest
import io.github.sepfins.limitguard.domain.limit.LimitAmount
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

        // A policy delay is a floor: it postpones a change but never pulls one the host
        // scheduled later back to the policy's time.
        val before = request.effectiveAt
        val effectiveAt = maxOf(action.effectiveAt, before ?: action.effectiveAt)
        val changed = before != effectiveAt
        val after = if (changed) {
            request.copy(effectiveAt = effectiveAt, updatedAt = at)
        } else {
            request
        }
        return SingleActionExecution(
            request = after,
            result = mapOf(
                "type" to "SET_EFFECTIVE_AT",
                "before" to before?.toString(),
                "after" to effectiveAt.toString(),
                "changed" to changed,
            ),
        )
    }

    private fun capRequestedAmount(
        request: LimitRuleChangeRequest,
        action: PolicyAction.CapRequestedAmount,
        at: Instant,
    ): SingleActionExecution {
        val before = request.requestedAmount
        val currency = before.currency
        require(action.maximumAmount.currency == currency) {
            "CAP_REQUESTED_AMOUNT currency must match the requested amount currency"
        }
        val maximum = LimitAmount.Bounded(action.maximumAmount)
        val changed = !before.fitsWithin(maximum)
        val afterAmount = if (changed) maximum else before
        val after = if (changed) {
            request.copy(requestedAmount = afterAmount, updatedAt = at)
        } else {
            request
        }
        return SingleActionExecution(
            request = after,
            result = mapOf(
                "type" to "CAP_REQUESTED_AMOUNT",
                "maximum" to action.maximumAmount.amount.toString(),
                "currency" to currency.value,
                "before" to before.boundedMoney?.amount?.toString(),
                "after" to afterAmount.boundedMoney?.amount?.toString(),
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
