package io.github.sepfins.limitguard.domain.funding

import io.github.sepfins.limitguard.domain.common.FundingSourceGroupId
import io.github.sepfins.limitguard.domain.common.Metadata
import io.github.sepfins.limitguard.domain.common.PartyProfileId
import io.github.sepfins.limitguard.domain.common.TenantId
import java.time.Instant

data class FundingSourceGroup(
    val id: FundingSourceGroupId,
    val tenantId: TenantId,
    val ownerPartyProfileId: PartyProfileId,
    val code: FundingSourceGroupCode,
    val name: FundingSourceGroupName,
    val metadata: Metadata = Metadata.EMPTY,
    val createdAt: Instant? = null
)
