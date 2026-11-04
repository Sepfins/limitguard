package io.github.sepfins.limitguard.domain.tenant

import io.github.sepfins.limitguard.domain.common.Metadata
import io.github.sepfins.limitguard.domain.common.TenantId
import java.time.Instant
import java.time.ZoneId

data class Tenant(
    val id: TenantId,
    val name: TenantName,
    val timeZone: ZoneId,
    val config: Metadata = Metadata.EMPTY,
    val noMatchingRuleBehavior: NoMatchingRuleBehavior = NoMatchingRuleBehavior.DENY,
    val createdAt: Instant? = null
)
