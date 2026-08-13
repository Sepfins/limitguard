package io.github.sepfins.limitguard.application.evaluation

import io.github.sepfins.limitguard.api.LimitUsageEventType as ApiLimitUsageEventType
import io.github.sepfins.limitguard.api.TransactionStatus
import io.github.sepfins.limitguard.api.results.UsageMutationEvent
import io.github.sepfins.limitguard.api.results.UsageMutationResult
import io.github.sepfins.limitguard.application.transaction.RecordedMutation
import io.github.sepfins.limitguard.domain.usage.LimitUsageEvent
import io.github.sepfins.limitguard.domain.usage.LimitUsageEventType as DomainLimitUsageEventType
import io.github.sepfins.limitguard.domain.usage.UsageMutation

internal fun RecordedMutation.toUsageMutationResult(usageMutation: UsageMutation): UsageMutationResult {
    val mutationAmount = checkNotNull(event.amount) {
        "Mutation event ${event.id.value} has no amount"
    }
    return UsageMutationResult(
        tenantId = transaction.tenantId.value,
        paymentTransactionId = transaction.id.value,
        transactionEventId = event.id.value,
        status = TransactionStatus.valueOf(transaction.status.name),
        amount = mutationAmount.amount,
        currency = mutationAmount.currency.value,
        usageEvents = usageMutation.events.toUsageMutationEventList(),
        metadata = event.metadata.value,
    )
}

internal fun List<LimitUsageEvent>.toUsageMutationEventList(): List<UsageMutationEvent> =
    map { usageEvent ->
        UsageMutationEvent(
            limitRuleId = usageEvent.limitRuleId.value,
            usageBucketId = usageEvent.usageBucketId?.value,
            eventType = when(usageEvent.eventType) {
                DomainLimitUsageEventType.RESERVE -> ApiLimitUsageEventType.RESERVE
                DomainLimitUsageEventType.CONSUME -> ApiLimitUsageEventType.CONSUME
                DomainLimitUsageEventType.ADJUSTMENT -> ApiLimitUsageEventType.ADJUSTMENT
                DomainLimitUsageEventType.REVERSAL -> ApiLimitUsageEventType.REVERSAL
                DomainLimitUsageEventType.RELEASE -> ApiLimitUsageEventType.RELEASE
            },
            amountDelta = usageEvent.amountDelta,
            currency = usageEvent.currency.value,
        )
    }
