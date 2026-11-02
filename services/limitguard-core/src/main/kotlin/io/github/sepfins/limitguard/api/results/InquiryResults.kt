package io.github.sepfins.limitguard.api.results

import io.github.sepfins.limitguard.api.AuthorizationDecision
import io.github.sepfins.limitguard.api.LimitChangeActorType
import io.github.sepfins.limitguard.api.LimitAmount
import io.github.sepfins.limitguard.api.LimitKind
import io.github.sepfins.limitguard.api.LimitPeriod
import io.github.sepfins.limitguard.api.LimitRuleChangeEventType
import io.github.sepfins.limitguard.api.LimitRuleChangeStatus
import io.github.sepfins.limitguard.api.LimitRuleRevisionEventType
import io.github.sepfins.limitguard.api.OperationCapacityStatus
import io.github.sepfins.limitguard.api.TransactionEventType
import io.github.sepfins.limitguard.api.TransactionStatus
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

data class LimitChangeDetailsResult(
    val request: LimitChangeRequestResult,
    val events: List<LimitChangeEventResult>,
    val revisions: List<LimitRuleRevisionResult>,
)

/** A rule has no scope of its own: kind, period, currency, and scopes are its configuration's. */
data class LimitRuleResult(
    val id: UUID,
    val tenantId: UUID,
    val ownerPartyProfileId: UUID,
    val configurationId: UUID,
    /** Null applies the rule to the direct owner and every mandate of the owner. */
    val mandateId: UUID?,
    val amount: LimitAmount,
    val isActive: Boolean,
    val version: Int,
    val metadata: Map<String, Any?>,
    val createdAt: Instant?,
    val updatedAt: Instant?,
)

data class LimitChangeEventResult(
    val id: UUID,
    val eventType: LimitRuleChangeEventType,
    val actor: LimitChangeActorResult,
    val policyId: UUID?,
    val beforeStatus: LimitRuleChangeStatus?,
    val afterStatus: LimitRuleChangeStatus?,
    val payload: Map<String, Any?>,
    val message: String?,
    val createdAt: Instant,
)

data class LimitRuleRevisionResult(
    val id: UUID,
    val limitRuleId: UUID,
    val revisionNumber: Int,
    val eventType: LimitRuleRevisionEventType,
    val actor: LimitChangeActorResult,
    /** Null for a CREATED revision. */
    val beforeAmount: LimitAmount?,
    val afterAmount: LimitAmount,
    val effectiveAt: Instant?,
    val metadata: Map<String, Any?>,
    val createdAt: Instant,
)

data class LimitChangeActorResult(
    val actorType: LimitChangeActorType,
    val partyProfileId: UUID? = null,
)

data class TransactionHistoryResult(
    val transaction: PaymentTransactionResult,
    val authorizationDecision: AuthorizationDecisionSummaryResult?,
    val events: List<TransactionHistoryEventResult>,
)

data class PaymentTransactionResult(
    val id: UUID,
    val tenantId: UUID,
    val actingPartyProfileId: UUID,
    val fundingSourceId: UUID,
    val operationTypeId: UUID,
    val channelId: UUID,
    val authorizedAmount: BigDecimal,
    val capturedAmount: BigDecimal,
    val uncapturedAmount: BigDecimal,
    val currency: String,
    val status: TransactionStatus,
    val expiresAt: Instant?,
    val metadata: Map<String, Any?>,
    val createdAt: Instant,
)

data class AuthorizationDecisionSummaryResult(
    val id: UUID,
    val decision: AuthorizationDecision,
    val requestedAmount: BigDecimal,
    val currency: String,
    val expiresAt: Instant?,
    val createdAt: Instant,
)

data class TransactionHistoryEventResult(
    val id: UUID,
    val eventType: TransactionEventType,
    val amount: BigDecimal?,
    val currency: String?,
    val reason: String?,
    val metadata: Map<String, Any?>,
    val createdAt: Instant,
)

data class CurrentUsageResult(
    val tenantId: UUID,
    val limitRuleId: UUID,
    val assigneePartyProfileId: UUID,
    val usageBucketId: UUID?,
    val period: LimitPeriod,
    val periodStartsAt: Instant,
    val periodEndsAt: Instant,
    val limitAmount: BigDecimal,
    val reservedAmount: BigDecimal,
    val consumedAmount: BigDecimal,
    val remainingAmount: BigDecimal,
    val currency: String,
    val calculatedAt: Instant,
    val updatedAt: Instant?,
)

/**
 * Advisory capacity snapshot for a business operation. A later authorization
 * is still authoritative because usage may change after this read completes.
 */
data class OperationCapacityResult(
    val tenantId: UUID,
    val actingPartyProfileId: UUID,
    val fundingSourceId: UUID,
    val operationTypeId: UUID,
    val channelId: UUID,
    val currency: String,
    val status: OperationCapacityStatus,
    val effectiveAvailableAmount: BigDecimal?,
    val limitingRuleIds: List<UUID>,
    val applicableLimits: List<ApplicableLimitUsageResult>,
    val reasons: List<OperationCapacityReasonResult>,
    val calculatedAt: Instant,
)

data class ApplicableLimitUsageResult(
    val limitRuleId: UUID,
    val ownerPartyProfileId: UUID,
    val name: String,
    val description: String?,
    val limitKind: LimitKind,
    val period: LimitPeriod?,
    val periodStartsAt: Instant?,
    val periodEndsAt: Instant?,
    val limitAmount: LimitAmount,
    val consumedAmount: BigDecimal?,
    val reservedAmount: BigDecimal?,
    /** Null for an unlimited rule, which never caps capacity. */
    val availableAmount: BigDecimal?,
    /** AVAILABLE, EXHAUSTED, or UNLIMITED for this rule on its own. */
    val status: OperationCapacityStatus,
    val currency: String,
    val usageBucketId: UUID?,
    val updatedAt: Instant?,
)

data class OperationCapacityReasonResult(
    val code: String,
    val message: String,
    val limitRuleId: UUID? = null,
)
