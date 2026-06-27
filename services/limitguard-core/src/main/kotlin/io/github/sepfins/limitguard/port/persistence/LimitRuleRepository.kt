package io.github.sepfins.limitguard.port.persistence

import io.github.sepfins.limitguard.domain.common.PartyProfileId
import io.github.sepfins.limitguard.domain.common.TenantId
import io.github.sepfins.limitguard.domain.limit.LimitRule
import kotlin.time.Instant

interface LimitRuleRepository {
    fun findActiveForOwner(tenantId: TenantId, owner: PartyProfileId, at: Instant): List<LimitRule>
    fun insert(tenantId: TenantId, limitRule: LimitRule): LimitRule
    fun update(tenantId: TenantId, limitRule: LimitRule): LimitRule
}