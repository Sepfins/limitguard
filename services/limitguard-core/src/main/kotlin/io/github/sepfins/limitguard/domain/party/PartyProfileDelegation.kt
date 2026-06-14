package io.github.sepfins.limitguard.domain.party

import io.github.sepfins.limitguard.domain.common.EffectiveWindow
import io.github.sepfins.limitguard.domain.common.PartyProfileDelegationId
import io.github.sepfins.limitguard.domain.common.PartyProfileId
import io.github.sepfins.limitguard.domain.common.TenantId
import io.github.sepfins.limitguard.domain.common.Metadata
import kotlin.time.Instant


data class PartyProfileDelegation(
    val id: PartyProfileDelegationId,
    val tenantId: TenantId,
    val ownerPartyProfileId: PartyProfileId,
    val delegatePartyProfileId: PartyProfileId,
    val delegationType: PartyProfileDelegationType,
    val effectiveWindow: EffectiveWindow,
    val metadata: Metadata = Metadata.EMPTY,
    val createdAt: Instant? = null
) {
    init {
        require(ownerPartyProfileId != delegatePartyProfileId) {
            "ownerPartyProfileId and delegatePartyProfileId must differ"
        }
    }
}