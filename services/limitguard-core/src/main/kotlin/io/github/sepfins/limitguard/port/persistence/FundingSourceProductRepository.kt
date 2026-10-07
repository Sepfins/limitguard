package io.github.sepfins.limitguard.port.persistence

import io.github.sepfins.limitguard.domain.common.FundingSourceProductId
import io.github.sepfins.limitguard.domain.common.TenantId
import io.github.sepfins.limitguard.domain.funding.FundingSourceCategory
import io.github.sepfins.limitguard.domain.funding.FundingSourceProduct
import io.github.sepfins.limitguard.domain.funding.FundingSourceProductCode
import java.time.Instant

interface FundingSourceProductRepository {
    fun findActive(tenantId: TenantId): List<FundingSourceProduct>

    /** Includes inactive children; activation does not change whether a product is structurally a leaf. */
    fun findByParent(tenantId: TenantId, parentId: FundingSourceProductId): List<FundingSourceProduct>
    fun findByAccountCategory(tenantId: TenantId, accountCategory: FundingSourceCategory): List<FundingSourceProduct>
    fun findByCode(tenantId: TenantId, code: FundingSourceProductCode): FundingSourceProduct?
    fun findById(tenantId: TenantId, id: FundingSourceProductId): FundingSourceProduct?

    /**
     * The product and its ancestors, in no particular order and without validating them. It stops
     * at a missing parent and at a repeated node, so the caller detects both.
     */
    fun findPath(tenantId: TenantId, leafId: FundingSourceProductId): List<FundingSourceProduct>
    fun findPage(
        tenantId: TenantId,
        search: String?,
        active: Boolean?,
        offset: Int,
        limit: Int,
    ): List<FundingSourceProduct>
    fun count(tenantId: TenantId, search: String?, active: Boolean?): Long

    fun findByIdForUpdate(tenantId: TenantId, id: FundingSourceProductId): FundingSourceProduct?
    fun insert(tenantId: TenantId, product: FundingSourceProduct): FundingSourceProduct
    fun update(tenantId: TenantId, product: FundingSourceProduct): FundingSourceProduct
}
