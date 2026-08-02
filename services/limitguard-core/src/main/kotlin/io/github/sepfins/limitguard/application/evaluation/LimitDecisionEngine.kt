package io.github.sepfins.limitguard.application.evaluation

import io.github.sepfins.limitguard.domain.common.Money
import io.github.sepfins.limitguard.domain.limit.LimitRule
import io.github.sepfins.limitguard.domain.limit.LimitViolation
import io.github.sepfins.limitguard.domain.limit.MatchedLimitRule

internal data class LimitDecision(
    val matchedRules: List<MatchedLimitRule>,
    val violations: List<LimitViolation>,
) {
    val allowed: Boolean
        get() = violations.isEmpty()
}

internal object LimitDecisionEngine {
    fun decide(
        attemptedAmount: Money,
        rules: List<LimitRule>,
    ): LimitDecision {
        val matchedRules = rules.map { rule ->
            MatchedLimitRule(
                id = rule.id,
                name = rule.name,
                limitKind = rule.limitKind,
                amount = rule.amount,
                priority = rule.priority,
            )
        }
        val violations = rules
            .filter { attemptedAmount > it.amount }
            .map { rule ->
                LimitViolation(
                    code = "PER_TRANSACTION_LIMIT_EXCEEDED",
                    message = "Attempted amount ${attemptedAmount.amount} ${attemptedAmount.currency.value} " +
                        "exceeds per-transaction limit ${rule.amount.amount} ${rule.amount.currency.value}",
                    limitRuleId = rule.id,
                    limitAmount = rule.amount,
                    attemptedAmount = attemptedAmount,
                    remainingAmount = null,
                )
            }

        return LimitDecision(
            matchedRules = matchedRules,
            violations = violations,
        )
    }

    fun actorNotAuthorized(attemptedAmount: Money): LimitDecision =
        LimitDecision(
            matchedRules = emptyList(),
            violations = listOf(
                LimitViolation(
                    code = "ACTOR_NOT_AUTHORIZED",
                    message = "Acting party neither owns the funding source nor has an applicable mandate",
                    limitRuleId = null,
                    limitAmount = null,
                    attemptedAmount = attemptedAmount,
                    remainingAmount = null,
                ),
            ),
        )
}
