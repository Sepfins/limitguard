package io.github.sepfins.limitguard.domain.funding

import io.github.sepfins.limitguard.domain.common.FundingSourceProductId
import io.github.sepfins.limitguard.domain.common.Metadata
import io.github.sepfins.limitguard.domain.common.TenantId
import kotlin.time.Instant

data class FundingSourceProduct(
    val id: FundingSourceProductId,
    val tenantId: TenantId,
    val parentId: FundingSourceProductId?,
    val accountCategory: FundingSourceCategory,
    val nodeType: ProductNodeType,
    val code: FundingSourceProductCode,
    val name: FundingSourceProductName,
    val isLeaf: Boolean,
    val isActive: Boolean,
    val metadata: Metadata = Metadata.EMPTY,
    val createdAt: Instant? = null
)
