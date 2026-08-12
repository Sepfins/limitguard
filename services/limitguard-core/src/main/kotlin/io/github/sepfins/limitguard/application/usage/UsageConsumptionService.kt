package io.github.sepfins.limitguard.application.usage

import io.github.sepfins.limitguard.api.errors.NotFoundException
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
import java.time.Instant

class UsageConsumptionService(
    private val idGenerator: IdGenerator,
    private val transactionEventRepository: TransactionEventRepository,
    private val usageBucketRepository: UsageBucketRepository,
    private val usageEventRepository: UsageEventRepository,
) {
    fun consume(
        transaction: PaymentTransaction,
        captureEventId: TransactionEventId,
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

        val updatedBuckets = mutableListOf<LimitUsageBucket>()
        val usageEvents = mutableListOf<LimitUsageEvent>()

        for (reservedEvent in reservedEvents) {
            val bucketId = checkNotNull(reservedEvent.usageBucketId) {
                error("Reserve usage event ${reservedEvent.id.value} has no bucket")
            }

            val bucket = usageBucketRepository.findById(
                transaction.tenantId,
                bucketId
            ) ?: error("Usage bucket ${bucketId.value} not found")

            val consumedBucket = bucket
                .consume(amount)
                .copy(updatedAt = now)

            val persistedBucket = usageBucketRepository.update(
                transaction.tenantId,
                consumedBucket,
            )

            val consumeEvent = usageEventRepository.insert(
                tenantId = transaction.tenantId,
                LimitUsageEvent(
                    id = UsageEventId.of(idGenerator.newId()),
                    tenantId = transaction.tenantId,
                    limitRuleId = reservedEvent.limitRuleId,
                    usageBucketId = persistedBucket.id,
                    transactionEventId = captureEventId,
                    assigneePartyProfileId = transaction.actingPartyProfileId,
                    eventType = LimitUsageEventType.CONSUME,
                    amountDelta = amount.amount,
                    currency = amount.currency,
                    createdAt = now,
                )
            )

            updatedBuckets += persistedBucket
            usageEvents += consumeEvent
        }

        return UsageMutation(
            buckets = updatedBuckets,
            events = usageEvents,
        )
    }
}