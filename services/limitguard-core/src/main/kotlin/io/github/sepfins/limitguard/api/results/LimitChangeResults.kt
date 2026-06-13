package io.github.sepfins.limitguard.api.results

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
    val targetOwnerPartyProfileId: UUID,
    val targetMandateId: UUID?,
    val targetScopeHash: String,
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
