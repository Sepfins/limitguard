package io.github.sepfins.limitguard.application.evaluation

import io.github.sepfins.limitguard.application.applicability.ApplicabilityReason
import io.github.sepfins.limitguard.application.applicability.ApplicableLimitRule
import io.github.sepfins.limitguard.application.applicability.LimitApplicability
import io.github.sepfins.limitguard.application.cap
import io.github.sepfins.limitguard.application.usage.AvailableLimitCapacity
import io.github.sepfins.limitguard.application.usage.AvailableLimitCapacityResolver
import io.github.sepfins.limitguard.application.usage.HeldLimitCapacity
import io.github.sepfins.limitguard.domain.common.Money
import io.github.sepfins.limitguard.domain.common.TransactionEventId
import io.github.sepfins.limitguard.domain.limit.LimitViolation
import io.github.sepfins.limitguard.domain.limit.MatchedLimitRule
import io.github.sepfins.limitguard.domain.transaction.PaymentTransaction
import io.github.sepfins.limitguard.domain.transaction.AuthorizationDecisionOutcome
import io.github.sepfins.limitguard.domain.usage.UsageMutation
import java.time.Instant
import java.time.ZoneId

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
    val amount: Money,
    /** The held capacity an allowed decision reserves against; null when nothing may be reserved. */
    val held: HeldLimitCapacity?,
)

/**
 * Turns Limit Applicability into the authorization decision: every rule of the cascade is
 * checked against its Available Limit Capacity, held so that the reservation can follow.
 * Persisting the allowed transaction and the reservation remains orchestration work.
 */
internal class AuthorizationEvaluator(
    private val capacityResolver: AvailableLimitCapacityResolver,
) {
    fun evaluate(
        transaction: PaymentTransaction,
        applicability: LimitApplicability,
        now: Instant,
    ): AuthorizationEvaluation =
        when (applicability) {
            is LimitApplicability.Denied -> AuthorizationEvaluation(
                decision = decision(applicability.cascade, applicability.reasons.map { it.toViolation(transaction.authorizedAmount) }),
                amount = transaction.authorizedAmount,
                held = null,
            )
            is LimitApplicability.NotApplicable -> AuthorizationEvaluation(
                decision = LimitDecision(
                    outcome = AuthorizationDecisionOutcome.NOT_APPLICABLE,
                    matchedRules = emptyList(),
                    violations = emptyList(),
                ),
                amount = transaction.authorizedAmount,
                held = null,
            )
            is LimitApplicability.Unrestricted -> evaluateCascade(transaction, emptyList(), applicability.timeZone, now)
            is LimitApplicability.Applicable -> evaluateCascade(transaction, applicability.cascade, applicability.timeZone, now)
        }

    private fun evaluateCascade(
        transaction: PaymentTransaction,
        cascade: List<ApplicableLimitRule>,
        timeZone: ZoneId,
        now: Instant,
    ): AuthorizationEvaluation {
        val held = capacityResolver.hold(
            tenantId = transaction.tenantId,
            assigneePartyProfileId = transaction.actingPartyProfileId,
            at = now,
            timeZone = timeZone,
            cascade = cascade,
        )
        val violations = held.capacities
            .filterNot { it.admits(transaction.authorizedAmount) }
            .map { it.toViolation(transaction.authorizedAmount) }
        val decision = decision(cascade, violations)

        return AuthorizationEvaluation(
            decision = decision,
            amount = transaction.authorizedAmount,
            held = held.takeIf { decision.allowed },
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
        val held = checkNotNull(evaluation.held) {
            "Allowed authorization evaluation holds no capacity"
        }
        return capacityResolver.reserve(held, evaluation.amount, transactionEventId, now)
    }

    private fun decision(
        rules: List<ApplicableLimitRule>,
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

    private fun ApplicabilityReason.toViolation(attemptedAmount: Money): LimitViolation =
        LimitViolation(
            code = code,
            message = message,
            limitRuleId = limitRuleId,
            // AuthorizationResult has one currency field for attempted and limit amounts,
            // so a mismatched rule's amount is not reported as the limit amount.
            limitAmount = null,
            attemptedAmount = attemptedAmount,
            remainingAmount = null,
        )

    private fun AvailableLimitCapacity.toViolation(attemptedAmount: Money): LimitViolation =
        if (isPeriodic) {
            LimitViolation(
                code = "PERIODIC_LIMIT_EXCEEDED",
                message = "Attempted amount ${attemptedAmount.amount} ${attemptedAmount.currency.value} " +
                    "would exceed periodic limit ${rule.cap.amount} ${rule.cap.currency.value}",
                limitRuleId = rule.id,
                limitAmount = rule.cap,
                attemptedAmount = attemptedAmount,
                remainingAmount = remaining,
            )
        } else {
            LimitViolation(
                code = "PER_TRANSACTION_LIMIT_EXCEEDED",
                message = "Attempted amount ${attemptedAmount.amount} ${attemptedAmount.currency.value} " +
                    "exceeds per-transaction limit ${rule.cap.amount} ${rule.cap.currency.value}",
                limitRuleId = rule.id,
                limitAmount = rule.cap,
                attemptedAmount = attemptedAmount,
                remainingAmount = null,
            )
        }

    private fun ApplicableLimitRule.toMatchedRule(): MatchedLimitRule =
        MatchedLimitRule(
            id = rule.id,
            name = configuration.name,
            limitKind = configuration.limitKind,
            amount = rule.amount,
            priority = configuration.priority,
        )
}
