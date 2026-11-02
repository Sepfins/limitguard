package io.github.sepfins.limitguard.domain.change

import io.github.sepfins.limitguard.domain.common.IdempotencyKey
import io.github.sepfins.limitguard.domain.common.LimitChangeRequestId
import io.github.sepfins.limitguard.domain.common.LimitConfigurationId
import io.github.sepfins.limitguard.domain.common.LimitRuleId
import io.github.sepfins.limitguard.domain.common.MandateId
import io.github.sepfins.limitguard.domain.common.Metadata
import io.github.sepfins.limitguard.domain.common.PartyProfileDelegationId
import io.github.sepfins.limitguard.domain.common.PartyProfileId
import io.github.sepfins.limitguard.domain.common.TenantId
import io.github.sepfins.limitguard.domain.limit.LimitAmount
import io.github.sepfins.limitguard.domain.limit.LimitRuleTarget
import io.github.sepfins.limitguard.domain.mandate.MandateScope
import java.time.Instant

data class LimitRuleChangeRequest(
    val id: LimitChangeRequestId,
    val tenantId: TenantId,
    val changeType: LimitRuleChangeType,
    val requestedBy: ChangeActor,
    val targetOwnerPartyProfileId: PartyProfileId,
    val configurationId: LimitConfigurationId,
    /** The rule's amount once the change applies; for status changes, the amount it keeps. */
    val requestedAmount: LimitAmount,
    val status: LimitRuleChangeStatus = LimitRuleChangeStatus.REQUESTED,
    val limitRuleId: LimitRuleId? = null,
    val authorityDelegationId: PartyProfileDelegationId? = null,
    val requestActorContext: RequestActorContext,
    val requesterPartyProfileId: PartyProfileId? = null,
    val targetMandateId: MandateId? = null,
    val beforeAmount: LimitAmount? = null,
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
        // A CREATE may reference a deleted rule on its target, which it grants again.
        if (changeType == LimitRuleChangeType.CREATE) {
            require(beforeAmount == null) { "CREATE change must not carry a before amount" }
            require(status != LimitRuleChangeStatus.APPLIED || limitRuleId != null) {
                "APPLIED CREATE change must reference the created rule"
            }
        } else {
            require(limitRuleId != null) { "$changeType change must target an existing rule" }
            require(beforeAmount != null) { "$changeType change must carry a before amount" }
        }
        require(effectiveAt == null || expiresAt == null || expiresAt > effectiveAt) {
            "expiresAt must be after effectiveAt"
        }
    }

    val target: LimitRuleTarget
        get() = LimitRuleTarget(
            owner = targetOwnerPartyProfileId,
            configurationId = configurationId,
            mandateScope = targetMandateId?.let(MandateScope::MandateSpecific) ?: MandateScope.AllOwnerMandates,
        )

    val isAwaitingApproval: Boolean
        get() = status.isAwaitingApproval

    /** The deadline is exclusive: the request is elapsed from the instant it expires. */
    fun isElapsedAt(at: Instant): Boolean = expiresAt != null && expiresAt <= at

    /** An approved change whose application time has come. */
    fun isDueAt(at: Instant): Boolean =
        status.isAwaitingApplication && (effectiveAt == null || effectiveAt <= at)

    /** When the change applies if it is adopted at [now]: its effective time while that is ahead, else now. */
    fun applicationTimeFrom(now: Instant): Instant = effectiveAt?.takeIf { it > now } ?: now

    fun approve(
        reason: String?,
        at: Instant,
    ): LimitRuleChangeRequest {
        return transitionTo(LimitRuleChangeStatus.APPROVED).copy(
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
            decidedAt = at,
            decisionReason = reason,
            updatedAt = at,
        )
    }

    fun cancel(
        reason: String?,
        at: Instant,
    ): LimitRuleChangeRequest {
        return transitionTo(LimitRuleChangeStatus.CANCELLED).copy(
            cancelledAt = at,
            decisionReason = reason,
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
        reason: String,
        at: Instant,
    ): LimitRuleChangeRequest {
        return transitionTo(LimitRuleChangeStatus.FAILED).copy(
            decisionReason = reason,
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
        /** The failure reason recorded for an Elapsed Change Request, and the conflict refusing its approval. */
        const val DEADLINE_ELAPSED = "limit change request deadline elapsed"

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
