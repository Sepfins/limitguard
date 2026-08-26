package io.github.sepfins.limitguard.port.persistence

import io.github.sepfins.limitguard.domain.change.LimitChangePolicy
import io.github.sepfins.limitguard.domain.change.LimitChangePolicyCode
import io.github.sepfins.limitguard.domain.change.LimitChangePolicyPhase
import io.github.sepfins.limitguard.domain.common.LimitChangePolicyId
import io.github.sepfins.limitguard.domain.common.TenantId
import java.time.Instant

interface LimitChangePolicyRepository {
    fun findById(tenantId: TenantId, id: LimitChangePolicyId): LimitChangePolicy?
    fun findByCode(tenantId: TenantId, code: LimitChangePolicyCode): LimitChangePolicy?
    fun findActiveByPhase(
        tenantId: TenantId,
        phase: LimitChangePolicyPhase,
        at: Instant,
    ): List<LimitChangePolicy>
    fun insert(tenantId: TenantId, policy: LimitChangePolicy): LimitChangePolicy
    fun update(tenantId: TenantId, policy: LimitChangePolicy): LimitChangePolicy
}
