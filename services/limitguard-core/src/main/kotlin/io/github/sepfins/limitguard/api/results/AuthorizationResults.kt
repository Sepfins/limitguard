package io.github.sepfins.limitguard.api.results

import io.github.sepfins.limitguard.api.AuthorizationDecision
import io.github.sepfins.limitguard.api.LimitAmount
import io.github.sepfins.limitguard.api.LimitKind
import io.github.sepfins.limitguard.api.LimitPeriod
import io.github.sepfins.limitguard.api.LimitUsageEventType
import io.github.sepfins.limitguard.api.TransactionStatus
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

data class AuthorizationResult(
    val tenantId: UUID,
    val authorizationDecisionId: UUID,
    val decision: AuthorizationDecision,
    val paymentTransactionId: UUID?,
    val transactionEventId: UUID?,
    val authorizedAmount: BigDecimal,
    val currency: String,
    val matchedRules: List<MatchedLimitRule> = emptyList(),
    val violations: List<LimitViolation> = emptyList(),
    val metadata: Map<String, Any?> = emptyMap(),
)

data class MatchedLimitRule(
    val id: UUID,
    val name: String,
    val limitKind: LimitKind,
    val period: LimitPeriod?,
    val amount: LimitAmount,
    val currency: String,
    val priority: Int,
)

data class LimitViolation(
    val code: String,
    val message: String,
    val limitRuleId: UUID?,
    val limitAmount: BigDecimal?,
    val attemptedAmount: BigDecimal,
    val currency: String,
    val remainingAmount: BigDecimal? = null,
)

data class UsageMutationResult(
    val tenantId: UUID,
    val paymentTransactionId: UUID,
    val transactionEventId: UUID?,
    val status: TransactionStatus,
    val amount: BigDecimal,
    val currency: String,
    val usageEvents: List<UsageMutationEvent> = emptyList(),
    val metadata: Map<String, Any?> = emptyMap(),
)

data class UsageMutationEvent(
    val limitRuleId: UUID,
    val usageBucketId: UUID?,
    val eventType: LimitUsageEventType,
    val amountDelta: BigDecimal,
    val currency: String,
)

data class ExpireDueAuthorizationsResult(
    val tenantId: UUID,
    val processedAt: Instant,
    val expirations: List<UsageMutationResult>,
    val failures: List<FailedExpiration>,
)

data class FailedExpiration(
    val paymentTransactionId: UUID,
    val reason: String,
)
