package io.github.sepfins.limitguard.port.persistence

import io.github.sepfins.limitguard.domain.common.LimitConfigurationId
import io.github.sepfins.limitguard.domain.common.TenantId
import io.github.sepfins.limitguard.domain.limit.LimitChannelScope
import io.github.sepfins.limitguard.domain.limit.LimitConfiguration
import io.github.sepfins.limitguard.domain.limit.LimitConfigurationCode
import io.github.sepfins.limitguard.domain.limit.LimitConfigurationFundingScope
import io.github.sepfins.limitguard.domain.limit.LimitKind
import io.github.sepfins.limitguard.domain.limit.LimitOperationScope
import java.time.Instant

interface LimitConfigurationRepository {
    /**
     * Serializes tenant funding-envelope reads and writes through transaction completion.
     * Acquire before product/configuration row locks, then read the current catalog and hierarchy.
     */
    fun lockFundingEnvelopes(tenantId: TenantId)

    /** Includes disabled configurations and every effective window. */
    fun findAll(tenantId: TenantId): List<LimitConfiguration>
    fun findAllActive(tenantId: TenantId, at: Instant): List<LimitConfiguration>
    fun findActiveByChannelScope(tenantId: TenantId, scope: LimitChannelScope, at: Instant): List<LimitConfiguration>
    fun findActiveByLimitKind(tenantId: TenantId, kind: LimitKind, at: Instant): List<LimitConfiguration>
    fun findActiveByFundingScope(tenantId: TenantId, scope: LimitConfigurationFundingScope, at: Instant): List<LimitConfiguration>
    fun findActiveByOperationScope(tenantId: TenantId, scope: LimitOperationScope, at: Instant): List<LimitConfiguration>
    fun findById(tenantId: TenantId, id: LimitConfigurationId): LimitConfiguration?

    fun findByIdForUpdate(tenantId: TenantId, id: LimitConfigurationId): LimitConfiguration?
    fun findByCode(tenantId: TenantId, code: LimitConfigurationCode): LimitConfiguration?
    fun insert(tenantId: TenantId, limitConfiguration: LimitConfiguration): LimitConfiguration
    fun update(tenantId: TenantId, limitConfiguration: LimitConfiguration): LimitConfiguration
}
