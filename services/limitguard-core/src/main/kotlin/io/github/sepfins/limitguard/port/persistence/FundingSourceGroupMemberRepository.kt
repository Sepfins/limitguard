package io.github.sepfins.limitguard.port.persistence

import io.github.sepfins.limitguard.domain.common.FundingSourceGroupId
import io.github.sepfins.limitguard.domain.common.FundingSourceId
import io.github.sepfins.limitguard.domain.common.PartyProfileId
import io.github.sepfins.limitguard.domain.common.TenantId
import io.github.sepfins.limitguard.domain.funding.FundingSourceGroupMember

interface FundingSourceGroupMemberRepository {
    fun findByGroup(tenantId: TenantId, groupId: FundingSourceGroupId): List<FundingSourceGroupMember>

    fun findGroupIdsContaining(
        tenantId: TenantId,
        owner: PartyProfileId,
        fundingSourceId: FundingSourceId,
    ): Set<FundingSourceGroupId>

    fun insert(tenantId: TenantId, member: FundingSourceGroupMember): FundingSourceGroupMember
}
