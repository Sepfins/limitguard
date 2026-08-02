package io.github.sepfins.limitguard.port.persistence

import io.github.sepfins.limitguard.domain.common.FundingSourceGroupId
import io.github.sepfins.limitguard.domain.common.FundingSourceId
import io.github.sepfins.limitguard.domain.common.PartyProfileId
import io.github.sepfins.limitguard.domain.common.TenantId

interface FundingSourceGroupMemberRepository {
    fun findGroupIdsContaining(
        tenantId: TenantId,
        owner: PartyProfileId,
        fundingSourceId: FundingSourceId,
    ): Set<FundingSourceGroupId>
}
