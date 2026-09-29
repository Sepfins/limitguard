package io.github.sepfins.limitguard.application.usage

import io.github.sepfins.limitguard.domain.common.CurrencyCode
import io.github.sepfins.limitguard.domain.common.LimitRuleId
import io.github.sepfins.limitguard.domain.common.PartyProfileId
import io.github.sepfins.limitguard.domain.common.TenantId
import io.github.sepfins.limitguard.domain.common.UsageWindow
import io.github.sepfins.limitguard.domain.usage.LimitUsageBucket
import java.time.Instant

/**
 * Stable logical identity used when an operation needs to lock more than one
 * usage bucket. The order does not depend on rule evaluation priority or
 * bucket UUID allocation order.
 */
internal data class UsageBucketLockKey(
    val tenantId: TenantId,
    val limitRuleId: LimitRuleId,
    val assigneePartyProfileId: PartyProfileId,
    val currency: CurrencyCode,
    val periodStartedAt: Instant,
) : Comparable<UsageBucketLockKey> {
    override fun compareTo(other: UsageBucketLockKey): Int = compareValuesBy(
        this,
        other,
        { it.tenantId.value },
        { it.limitRuleId.value },
        { it.assigneePartyProfileId.value },
        { it.currency.value },
        { it.periodStartedAt },
    )

    companion object {
        fun forWindow(
            tenantId: TenantId,
            limitRuleId: LimitRuleId,
            assigneePartyProfileId: PartyProfileId,
            currency: CurrencyCode,
            window: UsageWindow,
        ): UsageBucketLockKey = UsageBucketLockKey(
            tenantId = tenantId,
            limitRuleId = limitRuleId,
            assigneePartyProfileId = assigneePartyProfileId,
            currency = currency,
            periodStartedAt = window.periodStartsAt,
        )

        fun forBucket(bucket: LimitUsageBucket): UsageBucketLockKey = forWindow(
            tenantId = bucket.tenantId,
            limitRuleId = bucket.limitRuleId,
            assigneePartyProfileId = bucket.assigneePartyProfileId,
            currency = bucket.currency,
            window = bucket.window,
        )
    }
}
