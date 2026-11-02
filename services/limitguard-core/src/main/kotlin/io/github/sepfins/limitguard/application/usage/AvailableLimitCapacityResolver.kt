package io.github.sepfins.limitguard.application.usage

import io.github.sepfins.limitguard.application.applicability.ApplicableLimitRule
import io.github.sepfins.limitguard.application.boundedMoney
import io.github.sepfins.limitguard.domain.common.Money
import io.github.sepfins.limitguard.domain.common.PartyProfileId
import io.github.sepfins.limitguard.domain.common.TenantId
import io.github.sepfins.limitguard.domain.common.TransactionEventId
import io.github.sepfins.limitguard.domain.common.UsageBucketId
import io.github.sepfins.limitguard.domain.common.UsageEventId
import io.github.sepfins.limitguard.domain.common.UsageWindow
import io.github.sepfins.limitguard.domain.limit.LimitKind
import io.github.sepfins.limitguard.domain.limit.LimitRule
import io.github.sepfins.limitguard.domain.usage.LimitUsageBucket
import io.github.sepfins.limitguard.domain.usage.LimitUsageEvent
import io.github.sepfins.limitguard.domain.usage.LimitUsageEventType
import io.github.sepfins.limitguard.domain.usage.UsageMutation
import io.github.sepfins.limitguard.port.ids.IdGenerator
import io.github.sepfins.limitguard.port.persistence.UsageBucketRepository
import io.github.sepfins.limitguard.port.persistence.UsageEventRepository
import java.time.Instant

/**
 * Resolves the Available Limit Capacity of a limit cascade for one assignee at one instant:
 * the Usage Window of each periodic rule, the Usage Bucket counting it, and what the rule
 * still admits. Reading answers an inquiry; holding locks the buckets so that a reservation
 * can follow in the same transaction.
 */
internal class AvailableLimitCapacityResolver(
    private val idGenerator: IdGenerator,
    private val usageBucketRepository: UsageBucketRepository,
    private val usageEventRepository: UsageEventRepository,
    private val usageWindowCalculator: UsageWindowCalculator,
) {
    /** The capacity of each rule, in cascade order, without locking anything. */
    fun read(
        tenantId: TenantId,
        assigneePartyProfileId: PartyProfileId,
        at: Instant,
        cascade: List<ApplicableLimitRule>,
    ): List<AvailableLimitCapacity> =
        capacities(tenantId, assigneePartyProfileId, at, cascade) { key ->
            usageBucketRepository.findByLimitRuleAndAssigneeInWindow(
                tenantId = tenantId,
                limitRuleId = key.rule.id,
                assigneeId = assigneePartyProfileId,
                currency = key.rule.amount.currency,
                window = key.window,
            )
        }

    /**
     * The capacity of each rule, in cascade order, with every periodic bucket key
     * locked for the surrounding transaction. Keys are acquired in one order independent of
     * the cascade so that concurrent reservations and mutations cannot deadlock.
     */
    fun hold(
        tenantId: TenantId,
        assigneePartyProfileId: PartyProfileId,
        at: Instant,
        cascade: List<ApplicableLimitRule>,
    ): HeldLimitCapacity =
        HeldLimitCapacity(
            tenantId = tenantId,
            assigneePartyProfileId = assigneePartyProfileId,
            capacities = capacities(tenantId, assigneePartyProfileId, at, cascade) { key ->
                usageBucketRepository.findForUpdate(
                    tenantId = tenantId,
                    limitRuleId = key.rule.id,
                    assigneePartyProfileId = assigneePartyProfileId,
                    currency = key.rule.amount.currency,
                    window = key.window,
                )
            },
        )

    /**
     * Reserves [amount] in every held periodic bucket, creating the window's bucket on first use.
     * Unlimited rules count too, so a cap set later sees everything the window already spent.
     */
    fun reserve(
        held: HeldLimitCapacity,
        amount: Money,
        transactionEventId: TransactionEventId,
        now: Instant,
    ): UsageMutation {
        val buckets = mutableListOf<LimitUsageBucket>()
        val events = mutableListOf<LimitUsageEvent>()

        for (capacity in held.capacities) {
            val window = capacity.window ?: continue
            val currency = capacity.rule.amount.currency
            // Only an unlimited rule can get here in another currency; a bounded one was denied.
            // Nothing converts, so its bucket cannot count this payment.
            if (amount.currency != currency) continue
            val bucket = capacity.bucket ?: LimitUsageBucket(
                id = UsageBucketId.of(idGenerator.newId()),
                tenantId = held.tenantId,
                limitRuleId = capacity.rule.id,
                assigneePartyProfileId = held.assigneePartyProfileId,
                window = window,
                reservedAmount = Money.zero(currency),
                consumedAmount = Money.zero(currency),
                createdAt = now,
                updatedAt = now,
            )
            val reserved = bucket.reserve(amount).copy(updatedAt = now)
            val persisted =
                if (capacity.bucket == null) {
                    usageBucketRepository.insert(held.tenantId, reserved)
                } else {
                    usageBucketRepository.update(held.tenantId, reserved)
                }
            val event = usageEventRepository.insert(
                held.tenantId,
                LimitUsageEvent(
                    id = UsageEventId.of(idGenerator.newId()),
                    tenantId = held.tenantId,
                    limitRuleId = capacity.rule.id,
                    usageBucketId = persisted.id,
                    transactionEventId = transactionEventId,
                    assigneePartyProfileId = held.assigneePartyProfileId,
                    eventType = LimitUsageEventType.RESERVE,
                    amountDelta = amount.amount,
                    currency = amount.currency,
                    createdAt = now,
                ),
            )
            buckets += persisted
            events += event
        }

        return UsageMutation(buckets = buckets, events = events)
    }

    private fun capacities(
        tenantId: TenantId,
        assigneePartyProfileId: PartyProfileId,
        at: Instant,
        cascade: List<ApplicableLimitRule>,
        find: (BucketKey) -> LimitUsageBucket?,
    ): List<AvailableLimitCapacity> {
        val windows = cascade.associate { it.rule.id to it.window(at) }
        val keys = cascade
            .mapNotNull { applicable -> windows.getValue(applicable.rule.id)?.let { BucketKey(tenantId, assigneePartyProfileId, applicable.rule, it) } }
        // Only the lock acquisition order is sorted; evaluation keeps the cascade order.
        val buckets = keys.sortedBy { it.lockKey }.associate { it.rule.id to find(it) }

        return cascade.map { applicable ->
            val cap = applicable.rule.amount.boundedMoney
            val bucket = buckets[applicable.rule.id]
            AvailableLimitCapacity(
                applicable = applicable,
                window = windows.getValue(applicable.rule.id),
                bucket = bucket,
                remaining = cap?.let { bucket?.remaining(it) ?: it },
            )
        }
    }

    private fun ApplicableLimitRule.window(at: Instant): UsageWindow? =
        (configuration.limitKind as? LimitKind.Periodic)?.let { usageWindowCalculator.calculateWindow(at, it.period) }

    private class BucketKey(
        tenantId: TenantId,
        assigneePartyProfileId: PartyProfileId,
        val rule: LimitRule,
        val window: UsageWindow,
    ) {
        val lockKey: UsageBucketLockKey = UsageBucketLockKey.forWindow(
            tenantId = tenantId,
            limitRuleId = rule.id,
            assigneePartyProfileId = assigneePartyProfileId,
            currency = rule.amount.currency,
            window = window,
        )
    }
}
