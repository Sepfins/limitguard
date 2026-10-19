package io.github.sepfins.limitguard.application.usage

import io.github.sepfins.limitguard.application.cap
import io.github.sepfins.limitguard.application.isBounded
import io.github.sepfins.limitguard.domain.common.Money
import io.github.sepfins.limitguard.domain.common.TransactionEventId
import io.github.sepfins.limitguard.domain.common.UsageBucketId
import io.github.sepfins.limitguard.domain.common.UsageEventId
import io.github.sepfins.limitguard.domain.common.UsageWindow
import io.github.sepfins.limitguard.application.evaluation.ApplicableLimitRule
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
        periodicRules: List<ApplicableLimitRule>,
        now: Instant,
    ): UsageReservationPlan {
        require(periodicRules.all { it.configuration.limitKind is LimitKind.Periodic && it.rule.isBounded }) {
            "Usage reservations can only be planned for bounded periodic rules"
        }

        val requests = periodicRules.map { applicable ->
            val rule = applicable.rule
            val period = (applicable.configuration.limitKind as LimitKind.Periodic).period
            val window = usageWindowCalculator.calculateWindow(now, period)
            ReservationBucketRequest(
                rule = rule,
                window = window,
                lockKey = UsageBucketLockKey.forWindow(
                    tenantId = transaction.tenantId,
                    limitRuleId = rule.id,
                    assigneePartyProfileId = transaction.actingPartyProfileId,
                    currency = transaction.authorizedAmount.currency,
                    window = window,
                ),
            )
        }

        // Evaluation order remains the caller's priority/cascade order. Only
        // the physical bucket acquisition order is normalized here.
        val existingBuckets = requests
            .sortedBy { it.lockKey }
            .associateWith { request ->
                usageBucketRepository.findForUpdate(
                    tenantId = transaction.tenantId,
                    limitRuleId = request.rule.id,
                    assigneePartyProfileId = transaction.actingPartyProfileId,
                    currency = transaction.authorizedAmount.currency,
                    window = request.window,
                )
            }

        val candidates = requests.map { request ->
            val existingBucket = existingBuckets.getValue(request)

            val candidateBucket = existingBucket ?: LimitUsageBucket(
                id = UsageBucketId.of(idGenerator.newId()),
                tenantId = transaction.tenantId,
                limitRuleId = request.rule.id,
                assigneePartyProfileId = transaction.actingPartyProfileId,
                window = request.window,
                reservedAmount = Money.zero(transaction.authorizedAmount.currency),
                consumedAmount = Money.zero(transaction.authorizedAmount.currency),
                createdAt = now,
                updatedAt = now,
            )

            UsageReservationCandidate(
                rule = request.rule,
                existingBucket = existingBucket,
                candidateBucket = candidateBucket,
            )
        }

        val exceeding = candidates.filter {
            it.candidateBucket.wouldExceed(
                limitAmount = it.rule.cap,
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

    private data class ReservationBucketRequest(
        val rule: LimitRule,
        val window: UsageWindow,
        val lockKey: UsageBucketLockKey,
    )

    private fun UsageReservationCandidate.toViolation(
        attemptedAmount: Money,
    ): LimitViolation =
        LimitViolation(
            code = "PERIODIC_LIMIT_EXCEEDED",
            message = "Attempted amount ${attemptedAmount.amount} ${attemptedAmount.currency.value} " +
                "would exceed periodic limit ${rule.cap.amount} ${rule.cap.currency.value}",
            limitRuleId = rule.id,
            limitAmount = rule.cap,
            attemptedAmount = attemptedAmount,
            remainingAmount = candidateBucket.remaining(rule.cap),
        )
}
