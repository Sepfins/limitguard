package io.github.sepfins.limitguard.application.usage

import io.github.sepfins.limitguard.domain.common.Money
import io.github.sepfins.limitguard.domain.common.TransactionEventId
import io.github.sepfins.limitguard.domain.common.UsageBucketId
import io.github.sepfins.limitguard.domain.common.UsageEventId
import io.github.sepfins.limitguard.domain.limit.LimitKind
import io.github.sepfins.limitguard.domain.limit.LimitRule
import io.github.sepfins.limitguard.domain.limit.LimitViolation
import io.github.sepfins.limitguard.domain.transaction.PaymentTransaction
import io.github.sepfins.limitguard.domain.usage.LimitUsageBucket
import io.github.sepfins.limitguard.domain.usage.LimitUsageEvent
import io.github.sepfins.limitguard.domain.usage.LimitUsageEventType
import io.github.sepfins.limitguard.domain.usage.UsageMutation
import io.github.sepfins.limitguard.port.ids.IdGenerator
import io.github.sepfins.limitguard.port.persistence.UsageBucketRepository
import io.github.sepfins.limitguard.port.persistence.UsageEventRepository
import java.time.Instant

internal class UsageReservationService(
    private val idGenerator: IdGenerator,
    private val usageBucketRepository: UsageBucketRepository,
    private val usageEventRepository: UsageEventRepository,
    private val usageWindowCalculator: UsageWindowCalculator,
) {
    fun planAuthorizationReservation(
        transaction: PaymentTransaction,
        periodicRules: List<LimitRule>,
        now: Instant,
    ): UsageReservationPlan {
        require(periodicRules.all { it.limitKind is LimitKind.Periodic }) {
            "Usage reservations can only be planned for periodic rules"
        }

        val candidates = periodicRules.map { rule ->
            val period = (rule.limitKind as LimitKind.Periodic).period
            val window = usageWindowCalculator.calculateWindow(now, period)

            val existingBucket = usageBucketRepository.findForUpdate(
                tenantId = transaction.tenantId,
                limitRuleId = rule.id,
                assigneePartyProfileId = transaction.actingPartyProfileId,
                currency = transaction.authorizedAmount.currency,
                window = window,
            )

            val candidateBucket = existingBucket ?: LimitUsageBucket(
                id = UsageBucketId.of(idGenerator.newId()),
                tenantId = transaction.tenantId,
                limitRuleId = rule.id,
                assigneePartyProfileId = transaction.actingPartyProfileId,
                window = window,
                reservedAmount = Money.zero(transaction.authorizedAmount.currency),
                consumedAmount = Money.zero(transaction.authorizedAmount.currency),
                createdAt = now,
                updatedAt = now,
            )

            UsageReservationCandidate(
                rule = rule,
                existingBucket = existingBucket,
                candidateBucket = candidateBucket,
            )
        }

        val exceeding = candidates.filter {
            it.candidateBucket.wouldExceed(
                limitAmount = it.rule.amount,
                delta = transaction.authorizedAmount
            )
        }

        val violations = exceeding.map { it.toViolation(transaction.authorizedAmount) }

        return UsageReservationPlan(
            transaction = transaction,
            candidates = candidates,
            violations = violations,
        )
    }

    fun reserve(
        plan: UsageReservationPlan,
        transactionEventId: TransactionEventId,
        now: Instant,
    ): UsageMutation {
        require(plan.allowed) {
            "Cannot reserve usage for a denied reservation plan"
        }
        val tenantId = plan.transaction.tenantId

        val updatedBuckets = mutableListOf<LimitUsageBucket>()
        val usageEvents = mutableListOf<LimitUsageEvent>()

        for (candidate in plan.candidates) {
            val reservedBucket = candidate.candidateBucket
                .reserve(plan.transaction.authorizedAmount)
                .copy(updatedAt = now)

            val persistedBucket =
                if (candidate.isNew) {
                    usageBucketRepository.insert(tenantId, reservedBucket)
                } else {
                    usageBucketRepository.update(tenantId, reservedBucket)
                }

            val event = usageEventRepository.insert(
                tenantId,
                LimitUsageEvent(
                    id = UsageEventId.of(idGenerator.newId()),
                    tenantId = tenantId,
                    limitRuleId = candidate.rule.id,
                    usageBucketId = persistedBucket.id,
                    transactionEventId = transactionEventId,
                    assigneePartyProfileId = plan.transaction.actingPartyProfileId,
                    eventType = LimitUsageEventType.RESERVE,
                    amountDelta = plan.transaction.authorizedAmount.amount,
                    currency = plan.transaction.authorizedAmount.currency,
                    createdAt = now,
                ),
            )

            updatedBuckets += persistedBucket
            usageEvents += event
        }

        return UsageMutation(
            buckets = updatedBuckets,
            events = usageEvents,
        )
    }

    private fun UsageReservationCandidate.toViolation(
        attemptedAmount: Money,
    ): LimitViolation =
        LimitViolation(
            code = "PERIODIC_LIMIT_EXCEEDED",
            message = "Attempted amount ${attemptedAmount.amount} ${attemptedAmount.currency.value} " +
                "would exceed periodic limit ${rule.amount.amount} ${rule.amount.currency.value}",
            limitRuleId = rule.id,
            limitAmount = rule.amount,
            attemptedAmount = attemptedAmount,
            remainingAmount = candidateBucket.remaining(rule.amount),
        )
}
