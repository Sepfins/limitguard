package io.github.sepfins.limitguard.application.evaluation

import io.github.sepfins.limitguard.application.usage.UsageReservationPlan
import io.github.sepfins.limitguard.application.usage.UsageReservationService
import io.github.sepfins.limitguard.domain.common.Money
import io.github.sepfins.limitguard.domain.common.TransactionEventId
import io.github.sepfins.limitguard.domain.limit.LimitKind
import io.github.sepfins.limitguard.domain.limit.LimitRule
import io.github.sepfins.limitguard.domain.limit.LimitViolation
import io.github.sepfins.limitguard.domain.limit.MatchedLimitRule
import io.github.sepfins.limitguard.domain.transaction.PaymentTransaction
import io.github.sepfins.limitguard.domain.transaction.AuthorizationDecisionOutcome
import io.github.sepfins.limitguard.domain.tenant.NoMatchingRuleBehavior
import io.github.sepfins.limitguard.domain.usage.UsageMutation
import java.time.Instant

internal data class LimitDecision(
    val outcome: AuthorizationDecisionOutcome,
    val matchedRules: List<MatchedLimitRule>,
    val violations: List<LimitViolation>,
) {
    init {
        when (outcome) {
            AuthorizationDecisionOutcome.ALLOWED -> require(violations.isEmpty()) {
                "Allowed authorization decisions must not contain violations"
            }
            AuthorizationDecisionOutcome.DENIED -> require(violations.isNotEmpty()) {
                "Denied authorization decisions must contain at least one violation"
            }
            AuthorizationDecisionOutcome.NOT_APPLICABLE -> {
                require(matchedRules.isEmpty() && violations.isEmpty()) {
                    "Not-applicable decisions must not contain matched rules or violations"
                }
            }
        }
    }

    val allowed: Boolean
        get() = outcome == AuthorizationDecisionOutcome.ALLOWED
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
        noMatchingRuleBehavior: NoMatchingRuleBehavior,
        now: Instant,
    ): AuthorizationEvaluation {
        if (matchedRules.isEmpty()) {
            when (noMatchingRuleBehavior) {
                NoMatchingRuleBehavior.ALLOW -> Unit
                NoMatchingRuleBehavior.DENY -> return denied(
                    rules = emptyList(),
                    violations = listOf(
                        LimitViolation(
                            code = "NO_MATCHING_LIMIT_RULE",
                            message = "No active limit rule applies to this authorization",
                            limitRuleId = null,
                            limitAmount = null,
                            attemptedAmount = transaction.authorizedAmount,
                            remainingAmount = null,
                        ),
                    ),
                )
                NoMatchingRuleBehavior.NOT_APPLICABLE -> return AuthorizationEvaluation(
                    decision = LimitDecision(
                        outcome = AuthorizationDecisionOutcome.NOT_APPLICABLE,
                        matchedRules = emptyList(),
                        violations = emptyList(),
                    ),
                    reservationPlan = null,
                )
            }
        }

        val evaluatedRules = ApplicableLimitRuleSelector.select(matchedRules)
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
            outcome = if (violations.isEmpty()) {
                AuthorizationDecisionOutcome.ALLOWED
            } else {
                AuthorizationDecisionOutcome.DENIED
            },
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
                outcome = AuthorizationDecisionOutcome.DENIED,
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

        fun deniedForUnavailableReferences(
            attemptedAmount: Money,
            unavailableReferences: List<Pair<String, String>>,
        ): LimitDecision =
            LimitDecision(
                outcome = AuthorizationDecisionOutcome.DENIED,
                matchedRules = emptyList(),
                violations = unavailableReferences.map { (code, message) ->
                    LimitViolation(
                        code = code,
                        message = message,
                        limitRuleId = null,
                        limitAmount = null,
                        attemptedAmount = attemptedAmount,
                        remainingAmount = null,
                    )
                },
            )
    }
}
