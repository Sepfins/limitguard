package io.github.sepfins.limitguard.application.evaluation

import io.github.sepfins.limitguard.api.AuthorizationDecision
import io.github.sepfins.limitguard.api.LimitKind
import io.github.sepfins.limitguard.api.LimitPeriod
import io.github.sepfins.limitguard.api.results.AuthorizationResult
import io.github.sepfins.limitguard.api.results.LimitViolation as LimitViolationResult
import io.github.sepfins.limitguard.api.results.MatchedLimitRule as MatchedLimitRuleResult
import io.github.sepfins.limitguard.domain.common.TransactionEventId
import io.github.sepfins.limitguard.domain.common.AuthorizationDecisionId
import io.github.sepfins.limitguard.domain.limit.LimitKind as DomainLimitKind
import io.github.sepfins.limitguard.domain.limit.LimitViolation
import io.github.sepfins.limitguard.domain.limit.MatchedLimitRule
import io.github.sepfins.limitguard.domain.transaction.PaymentTransaction

internal fun PaymentTransaction.toAuthorizationResult(
    authorizationDecisionId: AuthorizationDecisionId,
    decision: LimitDecision,
    transactionEventId: TransactionEventId? = null,
): AuthorizationResult =
    AuthorizationResult(
        tenantId = tenantId.value,
        authorizationDecisionId = authorizationDecisionId.value,
        decision = AuthorizationDecision.valueOf(decision.outcome.name),
        paymentTransactionId = if (decision.allowed) id.value else null,
        transactionEventId = transactionEventId?.value,
        authorizedAmount = authorizedAmount.amount,
        currency = authorizedAmount.currency.value,
        matchedRules = decision.matchedRules.map { it.toResult() },
        violations = decision.violations.map { it.toResult() },
        metadata = metadata.value,
    )

private fun MatchedLimitRule.toResult(): MatchedLimitRuleResult {
    val (apiKind, apiPeriod) = limitKind.toApi()
    return MatchedLimitRuleResult(
        id = id.value,
        name = name.value,
        limitKind = apiKind,
        period = apiPeriod,
        amount = amount.amount,
        currency = amount.currency.value,
        priority = priority.value,
    )
}

private fun LimitViolation.toResult(): LimitViolationResult =
    LimitViolationResult(
        code = code,
        message = message,
        limitRuleId = limitRuleId?.value,
        limitAmount = limitAmount?.amount,
        attemptedAmount = attemptedAmount.amount,
        currency = attemptedAmount.currency.value,
        remainingAmount = remainingAmount?.amount,
    )

private fun DomainLimitKind.toApi(): Pair<LimitKind, LimitPeriod?> =
    when (this) {
        DomainLimitKind.PerTransaction -> LimitKind.PER_TRANSACTION to null
        is DomainLimitKind.Periodic -> LimitKind.PERIODIC to LimitPeriod.valueOf(period.name)
    }
