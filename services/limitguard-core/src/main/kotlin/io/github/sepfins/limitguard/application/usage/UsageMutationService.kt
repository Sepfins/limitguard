package io.github.sepfins.limitguard.application.usage

import io.github.sepfins.limitguard.api.errors.NotFoundException
import io.github.sepfins.limitguard.application.transaction.TransactionMutationKind
import io.github.sepfins.limitguard.domain.common.Money
import io.github.sepfins.limitguard.domain.common.TransactionEventId
import io.github.sepfins.limitguard.domain.common.UsageEventId
import io.github.sepfins.limitguard.domain.transaction.PaymentTransaction
import io.github.sepfins.limitguard.domain.usage.LimitUsageBucket
import io.github.sepfins.limitguard.domain.usage.LimitUsageEvent
import io.github.sepfins.limitguard.domain.usage.LimitUsageEventType
import io.github.sepfins.limitguard.domain.usage.UsageMutation
import io.github.sepfins.limitguard.port.ids.IdGenerator
import io.github.sepfins.limitguard.port.persistence.TransactionEventRepository
import io.github.sepfins.limitguard.port.persistence.UsageBucketRepository
import io.github.sepfins.limitguard.port.persistence.UsageEventRepository
import java.math.BigDecimal
import java.time.Instant

class UsageMutationService(
    private val idGenerator: IdGenerator,
    private val transactionEventRepository: TransactionEventRepository,
    private val usageBucketRepository: UsageBucketRepository,
    private val usageEventRepository: UsageEventRepository,
) {
    fun mutate(
        transaction: PaymentTransaction,
        mutationKind: TransactionMutationKind,
        mutationEventId: TransactionEventId,
        amount: Money,
        now: Instant
    ): UsageMutation {
        val authorizationEvent = transactionEventRepository.findAuthorizationEvent(
            tenantId = transaction.tenantId,
            transactionId = transaction.id,
        ) ?: throw NotFoundException(
            "Transaction ${transaction.tenantId.value} / ${transaction.id.value} has no authorization event",
        )

        val reservedEvents = usageEventRepository.findByTransactionEventAndType(
            tenantId = authorizationEvent.tenantId,
            transactionEventId = authorizationEvent.id,
            type = LimitUsageEventType.RESERVE,
        )

        if (reservedEvents.isEmpty()) {
            return UsageMutation()
        }

        val reservedBucketIds = reservedEvents.map { reservedEvent ->
            reservedEvent to checkNotNull(reservedEvent.usageBucketId) {
                "Reserve usage event ${reservedEvent.id.value} has no bucket"
            }
        }
        val bucketsById = usageBucketRepository
            .findByIds(transaction.tenantId, reservedBucketIds.mapTo(hashSetOf()) { it.second })
            .associateBy { it.id }
        val discoveredBuckets = reservedBucketIds.map { (reservedEvent, bucketId) ->
            val bucket = bucketsById[bucketId] ?: error("Usage bucket ${bucketId.value} not found")
            DiscoveredUsageBucket(
                reservedEvent = reservedEvent,
                bucket = bucket,
                lockKey = UsageBucketLockKey.forBucket(bucket),
            )
        }

        // Discover rows without locking first, then lock every logical key in
        // the same order authorization reservations use. Events and results
        // below stay in bucket UUID order.
        val lockedBuckets = discoveredBuckets
            .map { it.lockKey }
            .distinct()
            .sorted()
            .associateWith { lockKey ->
                val discovered = discoveredBuckets.first { it.lockKey == lockKey }
                usageBucketRepository.findForUpdate(
                    tenantId = transaction.tenantId,
                    limitRuleId = lockKey.limitRuleId,
                    assigneePartyProfileId = lockKey.assigneePartyProfileId,
                    currency = lockKey.currency,
                    window = discovered.bucket.window,
                ) ?: error(
                    "Usage bucket ${discovered.bucket.id.value} not found for logical lock key $lockKey"
                )
            }
            .toMutableMap()

        val updatedBuckets = mutableListOf<LimitUsageBucket>()
        val usageEvents = mutableListOf<LimitUsageEvent>()

        for (discovered in discoveredBuckets.sortedBy { it.bucket.id.value }) {
            val reservedEvent = discovered.reservedEvent
            val bucket = lockedBuckets.getValue(discovered.lockKey)

            val bucketMutation = bucket.applyMutation(
                mutationKind = mutationKind,
                amount = amount,
                updatedAt = now,
            )

            val persistedBucket = usageBucketRepository.update(
                transaction.tenantId,
                bucketMutation.bucket,
            )

            lockedBuckets[discovered.lockKey] = persistedBucket

            val mutateEvent = usageEventRepository.insert(
                tenantId = transaction.tenantId,
                LimitUsageEvent(
                    id = UsageEventId.of(idGenerator.newId()),
                    tenantId = transaction.tenantId,
                    limitRuleId = reservedEvent.limitRuleId,
                    usageBucketId = persistedBucket.id,
                    transactionEventId = mutationEventId,
                    assigneePartyProfileId = reservedEvent.assigneePartyProfileId,
                    eventType = bucketMutation.eventType,
                    amountDelta = bucketMutation.amountDelta,
                    currency = amount.currency,
                    createdAt = now,
                )
            )

            updatedBuckets += persistedBucket
            usageEvents += mutateEvent
        }

        return UsageMutation(
            buckets = updatedBuckets,
            events = usageEvents,
        )
    }

    private data class DiscoveredUsageBucket(
        val reservedEvent: LimitUsageEvent,
        val bucket: LimitUsageBucket,
        val lockKey: UsageBucketLockKey,
    )

    private fun LimitUsageBucket.applyMutation(
        mutationKind: TransactionMutationKind,
        amount: Money,
        updatedAt: Instant,
    ): BucketUsageMutation =
        when (mutationKind) {
            TransactionMutationKind.CAPTURE ->
                BucketUsageMutation(
                    bucket = consume(amount).copy(updatedAt = updatedAt),
                    eventType = LimitUsageEventType.CONSUME,
                    amountDelta = amount.amount,
                )

            TransactionMutationKind.RELEASE ->
                BucketUsageMutation(
                    bucket = release(amount).copy(updatedAt = updatedAt),
                    eventType = LimitUsageEventType.RELEASE,
                    amountDelta = amount.amount.negate(),
                )

            TransactionMutationKind.REVERSAL ->
                BucketUsageMutation(
                    bucket = reverse(amount).copy(updatedAt = updatedAt),
                    eventType = LimitUsageEventType.REVERSAL,
                    amountDelta = amount.amount.negate(),
                )

            TransactionMutationKind.EXPIRY ->
                BucketUsageMutation(
                    bucket = release(amount).copy(updatedAt = updatedAt),
                    eventType = LimitUsageEventType.RELEASE,
                    amountDelta = amount.amount.negate(),
                )
        }

    private data class BucketUsageMutation(
        val bucket: LimitUsageBucket,
        val eventType: LimitUsageEventType,
        val amountDelta: BigDecimal,
    )

}
