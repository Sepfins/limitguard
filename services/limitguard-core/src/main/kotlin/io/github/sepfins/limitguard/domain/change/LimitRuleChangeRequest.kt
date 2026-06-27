package io.github.sepfins.limitguard.domain.change

import io.github.sepfins.limitguard.domain.common.IdempotencyKey
import io.github.sepfins.limitguard.domain.common.LimitChangeRequestId
import io.github.sepfins.limitguard.domain.common.LimitRuleId
import io.github.sepfins.limitguard.domain.common.MandateId
import io.github.sepfins.limitguard.domain.common.Metadata
import io.github.sepfins.limitguard.domain.common.PartyProfileDelegationId
import io.github.sepfins.limitguard.domain.common.PartyProfileId
import io.github.sepfins.limitguard.domain.common.TenantId
import kotlin.time.Instant

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
            require(limitRuleId == null) { "CREATE change must not target an existing rule" }
            require(beforeRule == null) { "CREATE change must not carry a before-rule snapshot" }
        } else {
            require(limitRuleId != null) { "$changeType change must target an existing rule" }
        }
        require(effectiveAt == null || expiresAt == null || expiresAt > effectiveAt) {
            "expiresAt must be after effectiveAt"
        }
    }
}
