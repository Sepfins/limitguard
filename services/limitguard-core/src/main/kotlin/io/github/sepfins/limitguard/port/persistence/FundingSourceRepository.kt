package io.github.sepfins.limitguard.port.persistence

import io.github.sepfins.limitguard.domain.common.FundingSourceId
import io.github.sepfins.limitguard.domain.common.ExternalReference
import io.github.sepfins.limitguard.domain.common.TenantId
import io.github.sepfins.limitguard.domain.funding.FundingSource

interface FundingSourceRepository {
    fun findById(tenantId: TenantId, id: FundingSourceId): FundingSource?

    fun findByIdForUpdate(tenantId: TenantId, id: FundingSourceId): FundingSource?
    fun findByExternalReference(tenantId: TenantId, externalReference: ExternalReference): FundingSource?
    fun insert(tenantId: TenantId, fundingSource: FundingSource): FundingSource
    fun update(tenantId: TenantId, fundingSource: FundingSource): FundingSource
}
