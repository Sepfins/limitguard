package io.github.sepfins.limitguard.application.inquiry

import io.github.sepfins.limitguard.api.LimitKind as ApiLimitKind
import io.github.sepfins.limitguard.api.LimitPeriod as ApiLimitPeriod
import io.github.sepfins.limitguard.api.OperationCapacityStatus
import io.github.sepfins.limitguard.api.results.ApplicableLimitUsageResult
import io.github.sepfins.limitguard.api.results.OperationCapacityReasonResult
import io.github.sepfins.limitguard.api.results.OperationCapacityResult
import io.github.sepfins.limitguard.application.applicability.ApplicabilityReason
import io.github.sepfins.limitguard.application.applicability.ApplicableLimitRule
import io.github.sepfins.limitguard.application.applicability.LimitApplicability
import io.github.sepfins.limitguard.application.applicability.LimitApplicabilityResolver
import io.github.sepfins.limitguard.application.applicability.LimitEvaluationContext
import io.github.sepfins.limitguard.application.toApi
import io.github.sepfins.limitguard.application.usage.AvailableLimitCapacity
import io.github.sepfins.limitguard.application.usage.AvailableLimitCapacityResolver
import io.github.sepfins.limitguard.domain.common.CurrencyCode
import io.github.sepfins.limitguard.domain.limit.LimitKind
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/** Maps Limit Applicability and the cascade's Available Limit Capacity to the advisory operation capacity. */
internal class OperationCapacityReader(
    private val limitApplicabilityResolver: LimitApplicabilityResolver,
    private val capacityResolver: AvailableLimitCapacityResolver,
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
                applicableLimits = usage(context, at, applicability.timeZone, applicability.cascade),
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
            is LimitApplicability.Applicable -> capacity(context, currency, at, applicability.timeZone, applicability.cascade)
        }

    private fun capacity(
        context: LimitEvaluationContext,
        currency: CurrencyCode,
        at: Instant,
        timeZone: ZoneId,
        cascade: List<ApplicableLimitRule>,
    ): OperationCapacityResult {
        val applicableLimits = usage(context, at, timeZone, cascade)
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

    private fun usage(
        context: LimitEvaluationContext,
        at: Instant,
        timeZone: ZoneId,
        cascade: List<ApplicableLimitRule>,
    ): List<ApplicableLimitUsageResult> =
        capacityResolver.read(context.tenantId, context.actingPartyProfileId, at, timeZone, cascade).map { it.toUsageResult() }

    private fun AvailableLimitCapacity.toUsageResult(): ApplicableLimitUsageResult =
        ApplicableLimitUsageResult(
            limitRuleId = rule.id.value,
            ownerPartyProfileId = rule.ownerPartyProfileId.value,
            name = configuration.name.value,
            description = configuration.description,
            limitKind = if (isPeriodic) ApiLimitKind.PERIODIC else ApiLimitKind.PER_TRANSACTION,
            period = (configuration.limitKind as? LimitKind.Periodic)?.let { ApiLimitPeriod.valueOf(it.period.name) },
            periodStartsAt = window?.periodStartsAt,
            periodEndsAt = window?.periodEndsAt,
            limitAmount = rule.amount.toApi(),
            consumedAmount = consumed?.amount,
            reservedAmount = reserved?.amount,
            availableAmount = remaining?.amount,
            status = when {
                remaining == null -> OperationCapacityStatus.UNLIMITED
                remaining.isZero -> OperationCapacityStatus.EXHAUSTED
                else -> OperationCapacityStatus.AVAILABLE
            },
            currency = rule.amount.currency.value,
            usageBucketId = bucket?.id?.value,
            updatedAt = bucket?.updatedAt,
        )

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
