package io.github.sepfins.limitguard.domain.tenant

import io.github.sepfins.limitguard.domain.common.Metadata
import io.github.sepfins.limitguard.domain.common.TenantId
import java.time.Instant

data class Tenant(
    val id: TenantId,
    val name: TenantName,
    val config: Metadata = Metadata.EMPTY,
    val createdAt: Instant? = null
)
