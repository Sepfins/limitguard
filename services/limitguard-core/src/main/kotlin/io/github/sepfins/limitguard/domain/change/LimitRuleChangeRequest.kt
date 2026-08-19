package io.github.sepfins.limitguard.domain.change

import io.github.sepfins.limitguard.domain.common.IdempotencyKey
import io.github.sepfins.limitguard.domain.common.LimitChangeRequestId
import io.github.sepfins.limitguard.domain.common.LimitRuleId
import io.github.sepfins.limitguard.domain.common.MandateId
import io.github.sepfins.limitguard.domain.common.Metadata
import io.github.sepfins.limitguard.domain.common.PartyProfileDelegationId
import io.github.sepfins.limitguard.domain.common.PartyProfileId
import io.github.sepfins.limitguard.domain.common.TenantId
import java.time.Instant

data class LimitRuleChangeRequest(
    val id: LimitChangeRequestId,
    val tenantId: TenantId,
    val changeType: LimitRuleChangeType,
    val requestedBy: ChangeActor,
    val targetOwnerPartyProfileId: PartyProfileId,
    val targetScope: Metadata,
    val targetScopeHash: ScopeHash,
    val requestedRule: Metadata,
    val status: LimitRuleChangeStatus = LimitRuleChangeStatus.REQUESTED,
    val limitRuleId: LimitRuleId? = null,
    val authorityDelegationId: PartyProfileDelegationId? = null,
    val requestActorContext: RequestActorContext,
    val requesterPartyProfileId: PartyProfileId? = null,
    val targetMandateId: MandateId? = null,
    val beforeRule: Metadata? = null,
    val approvedRule: Metadata? = null,
    val policyContext: Metadata = Metadata.EMPTY,
    val effectiveAt: Instant? = null,
    val expiresAt: Instant? = null,
    val decidedAt: Instant? = null,
    val appliedAt: Instant? = null,
    val cancelledAt: Instant? = null,
    val supersededByChangeRequestId: LimitChangeRequestId? = null,
    val decisionReason: String? = null,
    val idempotencyKey: IdempotencyKey? = null,
    val metadata: Metadata = Metadata.EMPTY,
    val createdAt: Instant? = null,
    val updatedAt: Instant? = null
) {
    init {
        if (changeType == LimitRuleChangeType.CREATE) {
            require(beforeRule == null) { "CREATE change must not carry a before-rule snapshot" }
            require(status == LimitRuleChangeStatus.APPLIED || limitRuleId == null) {
                "CREATE change must not reference a rule before it is applied"
            }
            require(status != LimitRuleChangeStatus.APPLIED || limitRuleId != null) {
                "APPLIED CREATE change must reference the created rule"
            }
        } else {
            require(limitRuleId != null) { "$changeType change must target an existing rule" }
            require(beforeRule != null) { "$changeType change must carry a before-rule snapshot" }
        }
        require(effectiveAt == null || expiresAt == null || expiresAt > effectiveAt) {
            "expiresAt must be after effectiveAt"
        }
    }

    fun approve(
        approvedRule: Metadata,
        reason: String?,
        at: Instant,
        approvedTargetScope: Metadata = targetScope,
        approvedTargetScopeHash: ScopeHash = targetScopeHash,
        approvedTargetMandateId: MandateId? = targetMandateId,
    ): LimitRuleChangeRequest {
        return transitionTo(LimitRuleChangeStatus.APPROVED).copy(
            approvedRule = approvedRule,
            targetScope = approvedTargetScope,
            targetScopeHash = approvedTargetScopeHash,
            targetMandateId = approvedTargetMandateId,
            decidedAt = at,
            decisionReason = reason,
            updatedAt = at,
        )
    }

    fun schedule(
        effectiveAt: Instant,
        at: Instant,
    ): LimitRuleChangeRequest {
        return transitionTo(LimitRuleChangeStatus.PENDING_EFFECTIVE_DATE).copy(
            effectiveAt = effectiveAt,
            updatedAt = at,
        )
    }

    fun reject(
        reason: String?,
        at: Instant
    ): LimitRuleChangeRequest {
        return transitionTo(LimitRuleChangeStatus.REJECTED).copy(
            status = LimitRuleChangeStatus.REJECTED,
            decidedAt = at,
            decisionReason = reason,
            updatedAt = at,
        )
    }

    fun cancel(
        at: Instant,
    ): LimitRuleChangeRequest {
        return transitionTo(LimitRuleChangeStatus.CANCELLED).copy(
            status = LimitRuleChangeStatus.REJECTED,
            cancelledAt = at,
            updatedAt = at,
        )
    }

    fun supersede(
        by: LimitChangeRequestId,
        at: Instant,
    ): LimitRuleChangeRequest {
        return transitionTo(LimitRuleChangeStatus.SUPERSEDED).copy(
            supersededByChangeRequestId = by,
            expiresAt = at,
            updatedAt = at,
        )
    }

    fun fail(
        at: Instant,
    ): LimitRuleChangeRequest {
        return transitionTo(LimitRuleChangeStatus.FAILED).copy(
            cancelledAt = at,
            updatedAt = at,
        )
    }

    fun markApplied(
        limitRuleId: LimitRuleId,
        at: Instant,
    ): LimitRuleChangeRequest {
        require(canTransitionTo(LimitRuleChangeStatus.APPLIED)) {
            "Cannot transition limit change request from $status to ${LimitRuleChangeStatus.APPLIED}"
        }
        return copy(
            status = LimitRuleChangeStatus.APPLIED,
            limitRuleId = limitRuleId,
            appliedAt = at,
            updatedAt = at,
        )
    }

    private fun transitionTo(nextStatus: LimitRuleChangeStatus): LimitRuleChangeRequest {
        require(canTransitionTo(nextStatus)) {
            "Cannot transition limit change request from $status to $nextStatus"
        }
        return copy(status = nextStatus)
    }

    private fun canTransitionTo(nextStatus: LimitRuleChangeStatus): Boolean =
        nextStatus in allowedTransitions.getValue(status)

    companion object {
        private val allowedTransitions: Map<LimitRuleChangeStatus, Set<LimitRuleChangeStatus>> =
            mapOf(
                LimitRuleChangeStatus.REQUESTED to setOf(
                    LimitRuleChangeStatus.APPROVED,
                    LimitRuleChangeStatus.REJECTED,
                    LimitRuleChangeStatus.CANCELLED,
                    LimitRuleChangeStatus.SUPERSEDED,
                    LimitRuleChangeStatus.FAILED,
                ),
                LimitRuleChangeStatus.APPROVED to setOf(
                    LimitRuleChangeStatus.PENDING_EFFECTIVE_DATE,
                    LimitRuleChangeStatus.APPLIED,
                    LimitRuleChangeStatus.CANCELLED,
                    LimitRuleChangeStatus.SUPERSEDED,
                    LimitRuleChangeStatus.FAILED,
                ),
                LimitRuleChangeStatus.PENDING_EFFECTIVE_DATE to setOf(
                    LimitRuleChangeStatus.APPLIED,
                    LimitRuleChangeStatus.CANCELLED,
                    LimitRuleChangeStatus.SUPERSEDED,
                    LimitRuleChangeStatus.FAILED,
                ),
                LimitRuleChangeStatus.APPLIED to setOf(),
                LimitRuleChangeStatus.REJECTED to setOf(),
                LimitRuleChangeStatus.CANCELLED to setOf(),
                LimitRuleChangeStatus.SUPERSEDED to setOf(),
                LimitRuleChangeStatus.FAILED to setOf(),
            )
    }
}
