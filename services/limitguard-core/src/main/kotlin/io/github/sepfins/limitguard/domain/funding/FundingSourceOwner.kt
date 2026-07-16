package io.github.sepfins.limitguard.domain.funding

import io.github.sepfins.limitguard.domain.common.FundingSourceId
import io.github.sepfins.limitguard.domain.common.FundingSourceOwnerId
import io.github.sepfins.limitguard.domain.common.PartyProfileId
import io.github.sepfins.limitguard.domain.common.TenantId
import java.time.Instant

data class FundingSourceOwner(
    val id: FundingSourceOwnerId,
    val tenantId: TenantId,
    val partyProfileId: PartyProfileId,
    val fundingSourceId: FundingSourceId,
    val ownerRole: FundingSourceOwnerRole = FundingSourceOwnerRole.PRIMARY_OWNER,
    val createdAt: Instant? = null
)
