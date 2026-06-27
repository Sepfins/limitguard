package io.github.sepfins.limitguard.port.persistence

import io.github.sepfins.limitguard.domain.common.FundingSourceId
import io.github.sepfins.limitguard.domain.common.TenantId
import io.github.sepfins.limitguard.domain.funding.FundingSource

interface FundingSourceRepository {
    fun findById(tenantId: TenantId, id: FundingSourceId): FundingSource?
    fun insert(tenantId: TenantId, product: FundingSource): FundingSource
    fun update(tenantId: TenantId, product: FundingSource): FundingSource
}