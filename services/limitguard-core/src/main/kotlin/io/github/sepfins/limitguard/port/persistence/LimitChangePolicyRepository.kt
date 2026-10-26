package io.github.sepfins.limitguard.port.persistence

import io.github.sepfins.limitguard.domain.change.LimitChangePolicy
import io.github.sepfins.limitguard.domain.change.LimitChangePolicyCode
import io.github.sepfins.limitguard.domain.change.LimitChangePolicyPhase
import io.github.sepfins.limitguard.domain.common.LimitChangePolicyId
import io.github.sepfins.limitguard.domain.common.LimitConfigurationId
import io.github.sepfins.limitguard.domain.common.TenantId
import java.time.Instant

interface LimitChangePolicyRepository {
    fun findById(tenantId: TenantId, id: LimitChangePolicyId): LimitChangePolicy?
    fun findByCode(tenantId: TenantId, code: LimitChangePolicyCode): LimitChangePolicy?

    /**
     * Active policies of the phase that apply to [configurationId]: unbound ones, and those bound
     * to exactly it. A bound policy is exact-match only; configuration ancestry is not policy
     * inheritance.
     */
    fun findActiveForConfiguration(
        tenantId: TenantId,
        phase: LimitChangePolicyPhase,
        configurationId: LimitConfigurationId,
        at: Instant,
    ): List<LimitChangePolicy>

    /** Pages every policy of the tenant, including inactive ones, ordered by priority then code. */
    fun findPage(
        tenantId: TenantId,
        phase: LimitChangePolicyPhase?,
        limitConfigurationId: LimitConfigurationId?,
        offset: Int,
        limit: Int,
    ): List<LimitChangePolicy>
    fun count(
        tenantId: TenantId,
        phase: LimitChangePolicyPhase?,
        limitConfigurationId: LimitConfigurationId?,
    ): Long

    fun insert(tenantId: TenantId, policy: LimitChangePolicy): LimitChangePolicy
    fun update(tenantId: TenantId, policy: LimitChangePolicy): LimitChangePolicy
}
