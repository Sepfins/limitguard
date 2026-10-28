package io.github.sepfins.limitguard.api.results

import io.github.sepfins.limitguard.api.LimitAmount
import io.github.sepfins.limitguard.api.LimitChangePolicyEvaluationOutcome
import io.github.sepfins.limitguard.api.LimitRuleChangeEventType
import io.github.sepfins.limitguard.api.LimitRuleChangeStatus
import io.github.sepfins.limitguard.api.LimitRuleChangeType
import java.time.Instant
import java.util.UUID

data class LimitChangeRequestResult(
    val id: UUID,
    val tenantId: UUID,
    val limitRuleId: UUID?,
    val changeType: LimitRuleChangeType,
    val status: LimitRuleChangeStatus,
    val requestedBy: LimitChangeActorResult,
    val requesterPartyProfileId: UUID?,
    val requestChannelId: UUID?,
    val authorityDelegationId: UUID?,
    val targetOwnerPartyProfileId: UUID,
    val targetMandateId: UUID?,
    val configurationId: UUID,
    /** The rule's amount once the change applies; a status change keeps the current amount. */
    val requestedAmount: LimitAmount,
    /** The rule's amount when the change was requested; null for a CREATE. */
    val beforeAmount: LimitAmount?,
    val effectiveAt: Instant?,
    val expiresAt: Instant?,
    val decidedAt: Instant?,
    val appliedAt: Instant?,
    val cancelledAt: Instant?,
    val decisionReason: String?,
    val metadata: Map<String, Any?>,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class PolicyEvaluationResult(
    val policyId: UUID,
    val matched: Boolean,
    val decision: String,
    val message: String? = null,
    val evaluatedAt: Instant,
)

data class ApplyDueChangesResult(
    val tenantId: UUID,
    val dueAt: Instant,
    val appliedCount: Int,
    val failedCount: Int,
    val appliedChangeRequestIds: List<UUID> = emptyList(),
    val failedChangeRequestIds: List<UUID> = emptyList(),
)

/** One relayed limit-change event. It carries party profile ids only, never personal data. */
data class LimitChangeEventMessage(
    /** Stable across redeliveries; consumers deduplicate on it. */
    val eventId: UUID,
    val tenantId: UUID,
    val changeRequestId: UUID,
    val eventType: LimitRuleChangeEventType,
    val actor: LimitChangeActorResult,
    val beforeStatus: LimitRuleChangeStatus?,
    val afterStatus: LimitRuleChangeStatus?,
    val occurredAt: Instant,
    val targetOwnerPartyProfileId: UUID,
    val configurationId: UUID,
    /** Null applies the rule to the owner and every applicable mandate. */
    val targetMandateId: UUID?,
    val limitRuleId: UUID?,
    val requesterPartyProfileId: UUID?,
    val requestChannelId: UUID?,
    val changeType: LimitRuleChangeType,
    val requestedAmount: LimitAmount,
    val currency: String,
    val effectiveAt: Instant?,
    /** Set on an APPLIED event only; null there when the rule was created. */
    val beforeAmount: LimitAmount?,
    /** Set on an APPLIED event only. */
    val afterAmount: LimitAmount?,
    /** Set on a POLICY_EVALUATED event only. */
    val policyId: UUID?,
    val policyOutcome: LimitChangePolicyEvaluationOutcome?,
)
