package io.github.sepfins.limitguard.port.persistence

import io.github.sepfins.limitguard.domain.common.LimitRuleId
import io.github.sepfins.limitguard.domain.common.TenantId
import io.github.sepfins.limitguard.domain.common.TransactionEventId
import io.github.sepfins.limitguard.domain.common.UsageBucketId
import io.github.sepfins.limitguard.domain.common.UsageEventId
import io.github.sepfins.limitguard.domain.usage.LimitUsageEvent
import io.github.sepfins.limitguard.domain.usage.LimitUsageEventType

interface UsageEventRepository {
    fun findById(tenantId: TenantId, id: UsageEventId): LimitUsageEvent?
    fun findByBucket(tenantId: TenantId, bucketId: UsageBucketId): List<LimitUsageEvent>
    fun findByTransactionEvent(tenantId: TenantId, transactionEventId: TransactionEventId): List<LimitUsageEvent>
    fun findByTransactionEventAndType(
        tenantId: TenantId,
        transactionEventId: TransactionEventId,
        type: LimitUsageEventType
    ): List<LimitUsageEvent>
    fun findByLimitRuleAndTransactionEvent(
        tenantId: TenantId,
        limitRuleId: LimitRuleId,
        transactionEventId: TransactionEventId,
    ): List<LimitUsageEvent>
    fun findByLimitRuleTransactionEventAndType(
        tenantId: TenantId,
        limitRuleId: LimitRuleId,
        transactionEventId: TransactionEventId,
        type: LimitUsageEventType
    ): LimitUsageEvent?
    fun insert(tenantId: TenantId, event: LimitUsageEvent): LimitUsageEvent
}
