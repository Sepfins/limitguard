package io.github.sepfins.limitguard.port.persistence

import io.github.sepfins.limitguard.domain.common.LimitRuleId
import io.github.sepfins.limitguard.domain.common.PartyProfileId
import io.github.sepfins.limitguard.domain.common.TenantId
import io.github.sepfins.limitguard.domain.common.Version
import io.github.sepfins.limitguard.domain.limit.LimitRule
import java.time.Instant

interface LimitRuleRepository {
    fun findById(tenantId: TenantId, id: LimitRuleId): LimitRule?

    fun findByIdForUpdate(tenantId: TenantId, id: LimitRuleId): LimitRule?

    fun findByOwner(tenantId: TenantId, owner: PartyProfileId): List<LimitRule>

    fun findActiveForOwner(tenantId: TenantId, owner: PartyProfileId, at: Instant): List<LimitRule>
    fun insert(tenantId: TenantId, limitRule: LimitRule): LimitRule

    /**
     * Replaces a rule only when its persisted version still equals
     * [expectedVersion]. Returns null when the row is missing or stale.
     */
    fun updateIfVersion(
        tenantId: TenantId,
        limitRule: LimitRule,
        expectedVersion: Version,
    ): LimitRule?
}
