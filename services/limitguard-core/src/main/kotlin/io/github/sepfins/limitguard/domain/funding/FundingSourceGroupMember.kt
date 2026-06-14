package io.github.sepfins.limitguard.domain.funding

import io.github.sepfins.limitguard.domain.common.FundingSourceGroupId
import io.github.sepfins.limitguard.domain.common.FundingSourceId
import io.github.sepfins.limitguard.domain.common.PartyProfileId
import io.github.sepfins.limitguard.domain.common.TenantId
import kotlin.time.Instant

data class FundingSourceGroupMember(
    val tenantId: TenantId,
    val groupId: FundingSourceGroupId,
    val ownerPartyProfileId: PartyProfileId,
    val fundingSourceId: FundingSourceId,
    val createdAt: Instant? = null
)
