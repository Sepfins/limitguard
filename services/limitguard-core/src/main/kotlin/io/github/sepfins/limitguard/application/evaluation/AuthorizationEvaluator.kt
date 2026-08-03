package io.github.sepfins.limitguard.application.evaluation

import io.github.sepfins.limitguard.application.usage.UsageReservationPlan
import io.github.sepfins.limitguard.application.usage.UsageReservationService
import io.github.sepfins.limitguard.domain.common.Money
import io.github.sepfins.limitguard.domain.common.TransactionEventId
import io.github.sepfins.limitguard.domain.limit.LimitKind
import io.github.sepfins.limitguard.domain.limit.LimitRule
import io.github.sepfins.limitguard.domain.limit.LimitUsageConsumptionMode
import io.github.sepfins.limitguard.domain.limit.LimitViolation
import io.github.sepfins.limitguard.domain.limit.MatchedLimitRule
import io.github.sepfins.limitguard.domain.transaction.PaymentTransaction
import io.github.sepfins.limitguard.domain.usage.UsageMutation
import java.time.Instant

internal data class LimitDecision(
    val matchedRules: List<MatchedLimitRule>,
    val violations: List<LimitViolation>,
) {
    val allowed: Boolean
        get() = violations.isEmpty()
}

internal data class AuthorizationEvaluation(
    val decision: LimitDecision,
    val reservationPlan: UsageReservationPlan?,
)

/**
 * Owns the complete authorization decision. Persistence of an allowed
 * transaction and its prepared usage reservation remains orchestration work.
 */
internal class AuthorizationEvaluator(
    private val usageReservationService: UsageReservationService,
) {
    fun evaluate(
        transaction: PaymentTransaction,
        matchedRules: List<LimitRule>,
        now: Instant,
    ): AuthorizationEvaluation {
        val evaluatedRules = selectRulesForEvaluation(matchedRules)
        val currencyViolations = evaluatedRules
            .filter { it.amount.currency != transaction.authorizedAmount.currency }
            .map { it.currencyMismatchViolation(transaction.authorizedAmount) }

        if (currencyViolations.isNotEmpty()) {
            return denied(evaluatedRules, currencyViolations)
        }

        val perTransactionViolations = evaluatedRules
            .filter { it.limitKind == LimitKind.PerTransaction }
            .filter { transaction.authorizedAmount > it.amount }
            .map { it.perTransactionViolation(transaction.authorizedAmount) }

        if (perTransactionViolations.isNotEmpty()) {
            return denied(evaluatedRules, perTransactionViolations)
        }

        val reservationPlan = usageReservationService.planAuthorizationReservation(
            transaction = transaction,
            periodicRules = evaluatedRules.filter { it.limitKind is LimitKind.Periodic },
            now = now,
        )
        val decision = decision(evaluatedRules, reservationPlan.violations)

        return AuthorizationEvaluation(
            decision = decision,
            reservationPlan = reservationPlan.takeIf { decision.allowed },
        )
    }

    fun reserve(
        evaluation: AuthorizationEvaluation,
        transactionEventId: TransactionEventId,
        now: Instant,
    ): UsageMutation {
        require(evaluation.decision.allowed) {
            "Cannot reserve usage for a denied authorization evaluation"
        }
        val reservationPlan = checkNotNull(evaluation.reservationPlan) {
            "Allowed authorization evaluation has no reservation plan"
        }
        return usageReservationService.reserve(reservationPlan, transactionEventId, now)
    }

    private fun selectRulesForEvaluation(matchedRules: List<LimitRule>): List<LimitRule> {
        val selectedPeriodicRuleIds = matchedRules
            .filter { it.limitKind is LimitKind.Periodic }
            .groupBy { it.ownerPartyProfileId }
            .values
            .flatMap(::selectPeriodicCascade)
            .mapTo(hashSetOf()) { it.id }

        return matchedRules.filter { rule ->
            rule.limitKind == LimitKind.PerTransaction || rule.id in selectedPeriodicRuleIds
        }
    }

    private fun selectPeriodicCascade(rules: List<LimitRule>): List<LimitRule> {
        val priorityTiers = rules
            .groupBy { it.priority }
            .toSortedMap(compareBy { it.value })
        val selected = mutableListOf<LimitRule>()

        for (tier in priorityTiers.values) {
            selected += tier
            if (tier.any { it.usageConsumptionMode == LimitUsageConsumptionMode.ISOLATED }) {
                break
            }
        }

        return selected
    }

    private fun denied(
        rules: List<LimitRule>,
        violations: List<LimitViolation>,
    ): AuthorizationEvaluation =
        AuthorizationEvaluation(
            decision = decision(rules, violations),
            reservationPlan = null,
        )

    private fun decision(
        rules: List<LimitRule>,
        violations: List<LimitViolation>,
    ): LimitDecision =
        LimitDecision(
            matchedRules = rules.map { it.toMatchedRule() },
            violations = violations,
        )

    private fun LimitRule.perTransactionViolation(attemptedAmount: Money): LimitViolation =
        LimitViolation(
            code = "PER_TRANSACTION_LIMIT_EXCEEDED",
            message = "Attempted amount ${attemptedAmount.amount} ${attemptedAmount.currency.value} " +
                "exceeds per-transaction limit ${amount.amount} ${amount.currency.value}",
            limitRuleId = id,
            limitAmount = amount,
            attemptedAmount = attemptedAmount,
            remainingAmount = null,
        )

    private fun LimitRule.currencyMismatchViolation(attemptedAmount: Money): LimitViolation =
        LimitViolation(
            code = "LIMIT_CURRENCY_MISMATCH",
            message = "Cannot evaluate ${attemptedAmount.currency.value} transaction against " +
                "${amount.currency.value} limit rule without currency conversion",
            limitRuleId = id,
            // AuthorizationResult currently has one currency field for both
            // attempted and limit amounts, so do not mislabel this amount.
            limitAmount = null,
            attemptedAmount = attemptedAmount,
            remainingAmount = null,
        )

    private fun LimitRule.toMatchedRule(): MatchedLimitRule =
        MatchedLimitRule(
            id = id,
            name = name,
            limitKind = limitKind,
            amount = amount,
            priority = priority,
        )

    companion object {
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
}
