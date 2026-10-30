package io.github.sepfins.limitguard.application.inquiry

import io.github.sepfins.limitguard.api.LimitKind as ApiLimitKind
import io.github.sepfins.limitguard.api.LimitPeriod as ApiLimitPeriod
import io.github.sepfins.limitguard.api.OperationCapacityStatus
import io.github.sepfins.limitguard.api.results.ApplicableLimitUsageResult
import io.github.sepfins.limitguard.api.results.OperationCapacityReasonResult
import io.github.sepfins.limitguard.api.results.OperationCapacityResult
import io.github.sepfins.limitguard.application.boundedMoney
import io.github.sepfins.limitguard.application.applicability.ApplicabilityReason
import io.github.sepfins.limitguard.application.applicability.ApplicableLimitRule
import io.github.sepfins.limitguard.application.applicability.LimitApplicability
import io.github.sepfins.limitguard.application.applicability.LimitApplicabilityResolver
import io.github.sepfins.limitguard.application.applicability.LimitEvaluationContext
import io.github.sepfins.limitguard.application.isBounded
import io.github.sepfins.limitguard.application.toApi
import io.github.sepfins.limitguard.application.usage.UsageWindowCalculator
import io.github.sepfins.limitguard.domain.common.CurrencyCode
import io.github.sepfins.limitguard.domain.common.Money
import io.github.sepfins.limitguard.domain.limit.LimitKind
import io.github.sepfins.limitguard.port.persistence.UsageBucketRepository
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/** Maps Limit Applicability to the advisory operation capacity and reads the cascade's buckets. */
internal class OperationCapacityReader(
    private val limitApplicabilityResolver: LimitApplicabilityResolver,
    private val usageBucketRepository: UsageBucketRepository,
    private val usageWindowCalculator: UsageWindowCalculator,
) {
    fun read(
        context: LimitEvaluationContext,
        currency: CurrencyCode,
        at: Instant,
    ): OperationCapacityResult =
        when (val applicability = limitApplicabilityResolver.resolve(context, currency, at)) {
            is LimitApplicability.Denied -> result(
                context = context,
                currency = currency,
                at = at,
                status = OperationCapacityStatus.UNAVAILABLE,
                applicableLimits = applicability.cascade.map { it.toUsageResult(context, at) },
                reasons = applicability.reasons.map { it.toResult() },
            )
            is LimitApplicability.NotApplicable -> result(
                context = context,
                currency = currency,
                at = at,
                status = OperationCapacityStatus.NOT_APPLICABLE,
            )
            is LimitApplicability.Unrestricted -> result(
                context = context,
                currency = currency,
                at = at,
                status = OperationCapacityStatus.UNLIMITED,
            )
            is LimitApplicability.Applicable -> capacity(context, currency, at, applicability.cascade)
        }

    private fun capacity(
        context: LimitEvaluationContext,
        currency: CurrencyCode,
        at: Instant,
        selectedRules: List<ApplicableLimitRule>,
    ): OperationCapacityResult {
        val applicableLimits = selectedRules.map { it.toUsageResult(context, at) }
        // Unlimited rules are reported as applicable but never cap capacity.
        val boundedLimits = applicableLimits.filter { it.availableAmount != null }
        if (boundedLimits.isEmpty()) {
            return result(
                context = context,
                currency = currency,
                at = at,
                status = OperationCapacityStatus.AVAILABLE,
                applicableLimits = applicableLimits,
            )
        }

        val effectiveAvailableAmount = boundedLimits.mapNotNull { it.availableAmount }.min()
        val limitingRuleIds = boundedLimits
            .filter { it.availableAmount?.compareTo(effectiveAvailableAmount) == 0 }
            .map { it.limitRuleId }

        return result(
            context = context,
            currency = currency,
            at = at,
            status = if (effectiveAvailableAmount.signum() == 0) {
                OperationCapacityStatus.EXHAUSTED
            } else {
                OperationCapacityStatus.AVAILABLE
            },
            effectiveAvailableAmount = effectiveAvailableAmount,
            limitingRuleIds = limitingRuleIds,
            applicableLimits = applicableLimits,
        )
    }

    private fun ApplicableLimitRule.toUsageResult(
        context: LimitEvaluationContext,
        at: Instant,
    ): ApplicableLimitUsageResult {
        val period = (configuration.limitKind as? LimitKind.Periodic)?.period
        val window = period?.let { usageWindowCalculator.calculateWindow(at, it) }
        // Unlimited rules reserve nothing, so they have no bucket to read.
        val bucket = window?.takeIf { rule.isBounded }?.let {
            usageBucketRepository.findByLimitRuleAndAssigneeInWindow(
                tenantId = context.tenantId,
                limitRuleId = rule.id,
                assigneeId = context.actingPartyProfileId,
                currency = rule.amount.currency,
                window = it,
            )
        }
        val zero = Money.zero(rule.amount.currency).amount
        val cap = rule.amount.boundedMoney

        return ApplicableLimitUsageResult(
            limitRuleId = rule.id.value,
            ownerPartyProfileId = rule.ownerPartyProfileId.value,
            name = configuration.name.value,
            description = configuration.description,
            limitKind = if (period == null) ApiLimitKind.PER_TRANSACTION else ApiLimitKind.PERIODIC,
            period = period?.let { ApiLimitPeriod.valueOf(it.name) },
            periodStartsAt = window?.periodStartsAt,
            periodEndsAt = window?.periodEndsAt,
            limitAmount = rule.amount.toApi(),
            consumedAmount = window?.let { bucket?.consumedAmount?.amount ?: zero },
            reservedAmount = window?.let { bucket?.reservedAmount?.amount ?: zero },
            availableAmount = cap?.let { bucket?.remaining(it)?.amount ?: it.amount },
            currency = rule.amount.currency.value,
            usageBucketId = bucket?.id?.value,
            updatedAt = bucket?.updatedAt,
        )
    }

    private fun ApplicabilityReason.toResult(): OperationCapacityReasonResult =
        OperationCapacityReasonResult(code = code, message = message, limitRuleId = limitRuleId?.value)

    private fun result(
        context: LimitEvaluationContext,
        currency: CurrencyCode,
        at: Instant,
        status: OperationCapacityStatus,
        effectiveAvailableAmount: BigDecimal? = null,
        limitingRuleIds: List<UUID> = emptyList(),
        applicableLimits: List<ApplicableLimitUsageResult> = emptyList(),
        reasons: List<OperationCapacityReasonResult> = emptyList(),
    ): OperationCapacityResult =
        OperationCapacityResult(
            tenantId = context.tenantId.value,
            actingPartyProfileId = context.actingPartyProfileId.value,
            fundingSourceId = context.fundingSourceId.value,
            operationTypeId = context.operationTypeId.value,
            channelId = context.channelId.value,
            currency = currency.value,
            status = status,
            effectiveAvailableAmount = effectiveAvailableAmount,
            limitingRuleIds = limitingRuleIds,
            applicableLimits = applicableLimits,
            reasons = reasons,
            calculatedAt = at,
        )
}
