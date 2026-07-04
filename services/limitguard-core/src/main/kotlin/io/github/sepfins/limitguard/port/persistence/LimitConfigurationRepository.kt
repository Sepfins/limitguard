package io.github.sepfins.limitguard.port.persistence

import io.github.sepfins.limitguard.domain.common.LimitConfigurationId
import io.github.sepfins.limitguard.domain.common.TenantId
import io.github.sepfins.limitguard.domain.limit.LimitChannelScope
import io.github.sepfins.limitguard.domain.limit.LimitConfiguration
import io.github.sepfins.limitguard.domain.limit.LimitConfigurationCode
import io.github.sepfins.limitguard.domain.limit.LimitConfigurationFundingScope
import io.github.sepfins.limitguard.domain.limit.LimitKind
import io.github.sepfins.limitguard.domain.limit.LimitOperationScope
import kotlin.time.Instant

interface LimitConfigurationRepository {
    fun findAllActive(tenantId: TenantId, at: Instant): List<LimitConfiguration>
    fun findActiveByChannelScope(tenantId: TenantId, scope: LimitChannelScope, at: Instant): List<LimitConfiguration>
    fun findActiveByLimitKind(tenantId: TenantId, kind: LimitKind, at: Instant): List<LimitConfiguration>
    fun findActiveByFundingScope(tenantId: TenantId, scope: LimitConfigurationFundingScope, at: Instant): List<LimitConfiguration>
    fun findActiveByOperationScope(tenantId: TenantId, scope: LimitOperationScope, at: Instant): List<LimitConfiguration>
    fun findById(tenantId: TenantId, id: LimitConfigurationId): LimitConfiguration?
    fun findByCode(tenantId: TenantId, code: LimitConfigurationCode): LimitConfiguration?
    fun insert(tenantId: TenantId, limitConfiguration: LimitConfiguration): LimitConfiguration
    fun update(tenantId: TenantId, limitConfiguration: LimitConfiguration): LimitConfiguration
}
