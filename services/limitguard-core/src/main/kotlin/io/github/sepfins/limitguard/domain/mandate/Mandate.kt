package io.github.sepfins.limitguard.domain.mandate

import io.github.sepfins.limitguard.domain.common.EffectiveWindow
import io.github.sepfins.limitguard.domain.common.MandateId
import io.github.sepfins.limitguard.domain.common.PartyProfileDelegationId
import io.github.sepfins.limitguard.domain.common.PartyProfileId
import io.github.sepfins.limitguard.domain.common.TenantId
import io.github.sepfins.limitguard.domain.common.Version
import io.github.sepfins.limitguard.domain.funding.FundingSourceScope
import io.github.sepfins.limitguard.domain.common.Metadata
import kotlin.time.Instant

data class Mandate(
    val id: MandateId,
    val tenantId: TenantId,
    val ownerPartyProfileId: PartyProfileId,
    val assigneePartyProfileId: PartyProfileId,
    val authorityDelegationId: PartyProfileDelegationId?,
    val name: MandateName,
    val description: String?,
    val fundingScope: FundingSourceScope,
    val isActive: Boolean,
    val version: Version,
    val effectiveWindow: EffectiveWindow,
    val metadata: Metadata = Metadata.EMPTY,
    val createdAt: Instant? = null,
    val updatedAt: Instant? = null
) {
    init {
        require(ownerPartyProfileId != assigneePartyProfileId) {
            "ownerPartyProfileId and assigneePartyProfileId must differ"
        }
    }
}
