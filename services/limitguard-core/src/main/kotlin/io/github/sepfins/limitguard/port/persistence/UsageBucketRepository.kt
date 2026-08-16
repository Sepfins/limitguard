package io.github.sepfins.limitguard.port.persistence

import io.github.sepfins.limitguard.domain.common.CurrencyCode
import io.github.sepfins.limitguard.domain.common.LimitRuleId
import io.github.sepfins.limitguard.domain.common.PartyProfileId
import io.github.sepfins.limitguard.domain.common.TenantId
import io.github.sepfins.limitguard.domain.common.UsageBucketId
import io.github.sepfins.limitguard.domain.common.UsageWindow
import io.github.sepfins.limitguard.domain.usage.LimitUsageBucket

interface UsageBucketRepository {
    fun findByLimitRuleInWindow(
        tenantId: TenantId,
        limitRuleId: LimitRuleId,
        window: UsageWindow,
    ): List<LimitUsageBucket>

    fun findByLimitRuleAndAssigneeInWindow(
        tenantId: TenantId,
        limitRuleId: LimitRuleId,
        assigneeId: PartyProfileId,
        currency: CurrencyCode,
        window: UsageWindow,
    ): LimitUsageBucket?

    fun findById(tenantId: TenantId, id: UsageBucketId): LimitUsageBucket?

    fun findByIdForUpdate(tenantId: TenantId, id: UsageBucketId): LimitUsageBucket?

    /**
     * Locks the logical bucket key for the surrounding transaction, including
     * when no bucket row exists yet. A database adapter must not implement this
     * as only SELECT FOR UPDATE on the bucket table, because an absent row
     * would leave concurrent first reservations unserialized.
     */
    fun findForUpdate(
        tenantId: TenantId,
        limitRuleId: LimitRuleId,
        assigneePartyProfileId: PartyProfileId,
        currency: CurrencyCode,
        window: UsageWindow,
    ): LimitUsageBucket?

    fun insert(tenantId: TenantId, usageBucket: LimitUsageBucket): LimitUsageBucket
    fun update(tenantId: TenantId, usageBucket: LimitUsageBucket): LimitUsageBucket
}
