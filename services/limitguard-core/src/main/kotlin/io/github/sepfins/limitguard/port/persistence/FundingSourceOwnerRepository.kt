package io.github.sepfins.limitguard.port.persistence

import io.github.sepfins.limitguard.domain.common.FundingSourceId
import io.github.sepfins.limitguard.domain.common.FundingSourceOwnerId
import io.github.sepfins.limitguard.domain.common.PartyProfileId
import io.github.sepfins.limitguard.domain.common.TenantId
import io.github.sepfins.limitguard.domain.funding.FundingSourceOwner

interface FundingSourceOwnerRepository {
    fun findById(id: FundingSourceOwnerId): FundingSourceOwner?
    fun findByOwner(tenantId: TenantId, owner: PartyProfileId): List<FundingSourceOwner>
    fun findByFundingSource(tenantId: TenantId, fundingSource: FundingSourceId): List<FundingSourceOwner>
    fun insert(tenantId: TenantId, fundingSourceOwner: FundingSourceOwner): FundingSourceOwner
    fun update(tenantId: TenantId, fundingSourceOwner: FundingSourceOwner): FundingSourceOwner
}