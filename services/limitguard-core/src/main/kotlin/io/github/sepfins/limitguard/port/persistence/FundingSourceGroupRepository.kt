package io.github.sepfins.limitguard.port.persistence

import io.github.sepfins.limitguard.domain.common.FundingSourceGroupId
import io.github.sepfins.limitguard.domain.common.PartyProfileId
import io.github.sepfins.limitguard.domain.common.TenantId
import io.github.sepfins.limitguard.domain.funding.FundingSourceGroup
import io.github.sepfins.limitguard.domain.funding.FundingSourceGroupCode

interface FundingSourceGroupRepository {
    fun findById(tenantId: TenantId, id: FundingSourceGroupId): FundingSourceGroup?
    fun findByCode(
        tenantId: TenantId,
        ownerPartyProfileId: PartyProfileId,
        code: FundingSourceGroupCode,
    ): FundingSourceGroup?
    fun insert(tenantId: TenantId, fundingSourceGroup: FundingSourceGroup): FundingSourceGroup
    fun update(tenantId: TenantId, fundingSourceGroup: FundingSourceGroup): FundingSourceGroup
}
