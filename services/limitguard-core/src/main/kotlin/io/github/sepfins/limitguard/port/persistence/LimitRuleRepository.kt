package io.github.sepfins.limitguard.port.persistence

import io.github.sepfins.limitguard.domain.common.LimitRuleId
import io.github.sepfins.limitguard.domain.common.MandateId
import io.github.sepfins.limitguard.domain.common.PartyProfileId
import io.github.sepfins.limitguard.domain.common.TenantId
import io.github.sepfins.limitguard.domain.common.Version
import io.github.sepfins.limitguard.domain.limit.LimitRule
import io.github.sepfins.limitguard.domain.limit.LimitRuleTarget

interface LimitRuleRepository {
    fun findById(tenantId: TenantId, id: LimitRuleId): LimitRule?

    fun findByIdForUpdate(tenantId: TenantId, id: LimitRuleId): LimitRule?

    /** Loads and locks the one rule a target can have, deleted or not. */
    fun findByTargetForUpdate(tenantId: TenantId, target: LimitRuleTarget): LimitRule?

    fun findByOwner(tenantId: TenantId, owner: PartyProfileId): List<LimitRule>

    /**
     * The owner's active rules that bind an actor holding [mandateIds]: the owner-wide ones, and
     * those narrowed to one of these mandates. A direct owner holds none, so only owner-wide rules
     * bind it.
     */
    fun findActiveForAuthority(tenantId: TenantId, owner: PartyProfileId, mandateIds: Set<MandateId>): List<LimitRule>
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
