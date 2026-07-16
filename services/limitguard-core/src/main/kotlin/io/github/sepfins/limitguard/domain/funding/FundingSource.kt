package io.github.sepfins.limitguard.domain.funding

import io.github.sepfins.limitguard.domain.common.ExternalReference
import io.github.sepfins.limitguard.domain.common.FundingSourceId
import io.github.sepfins.limitguard.domain.common.FundingSourceProductId
import io.github.sepfins.limitguard.domain.common.Metadata
import io.github.sepfins.limitguard.domain.common.TenantId
import java.time.Instant

data class FundingSource(
    val id: FundingSourceId,
    val tenantId: TenantId,
    val productId: FundingSourceProductId,
    val externalReference: ExternalReference? = null,
    val isActive: Boolean,
    val metadata: Metadata = Metadata.EMPTY,
    val createdAt: Instant? = null
)
