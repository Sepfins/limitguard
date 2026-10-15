package io.github.sepfins.limitguard.application.inquiry

import io.github.sepfins.limitguard.api.LimitKind as ApiLimitKind
import io.github.sepfins.limitguard.api.LimitPeriod as ApiLimitPeriod
import io.github.sepfins.limitguard.api.OperationCapacityStatus
import io.github.sepfins.limitguard.api.errors.NotFoundException
import io.github.sepfins.limitguard.api.results.ApplicableLimitUsageResult
import io.github.sepfins.limitguard.api.results.OperationCapacityReasonResult
import io.github.sepfins.limitguard.api.results.OperationCapacityResult
import io.github.sepfins.limitguard.application.cap
import io.github.sepfins.limitguard.application.boundedMoney
import io.github.sepfins.limitguard.application.evaluation.ApplicableLimitRule
import io.github.sepfins.limitguard.application.evaluation.ApplicableLimitRuleSelector
import io.github.sepfins.limitguard.application.isBounded
import io.github.sepfins.limitguard.application.toApi
import io.github.sepfins.limitguard.application.evaluation.AuthorizationReferenceResolver
import io.github.sepfins.limitguard.application.evaluation.LimitEvaluationContext
import io.github.sepfins.limitguard.application.evaluation.LimitRuleMatcher
import io.github.sepfins.limitguard.application.evaluation.MandateResolver
import io.github.sepfins.limitguard.application.usage.UsageWindowCalculator
import io.github.sepfins.limitguard.domain.common.CurrencyCode
import io.github.sepfins.limitguard.domain.common.Money
import io.github.sepfins.limitguard.domain.limit.LimitKind
import io.github.sepfins.limitguard.domain.tenant.NoMatchingRuleBehavior
import io.github.sepfins.limitguard.port.persistence.FundingSourceOwnerRepository
import io.github.sepfins.limitguard.port.persistence.UsageBucketRepository
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

internal class OperationCapacityReader(
    private val authorizationReferenceResolver: AuthorizationReferenceResolver,
    private val fundingSourceOwnerRepository: FundingSourceOwnerRepository,
    private val mandateResolver: MandateResolver,
    private val limitRuleMatcher: LimitRuleMatcher,
    private val usageBucketRepository: UsageBucketRepository,
    private val usageWindowCalculator: UsageWindowCalculator,
) {
    fun read(
        context: LimitEvaluationContext,
        currency: CurrencyCode,
        at: Instant,
    ): OperationCapacityResult {
        val references = authorizationReferenceResolver.resolve(context)
        val unavailableReferences = references.unavailableReferenceViolations()
        if (unavailableReferences.isNotEmpty()) {
            return result(
                context = context,
                currency = currency,
                at = at,
                status = OperationCapacityStatus.UNAVAILABLE,
                reasons = unavailableReferences.map { (code, message) ->
                    OperationCapacityReasonResult(code = code, message = message)
                },
            )
        }

        val owners = fundingSourceOwnerRepository.findByFundingSource(
            tenantId = context.tenantId,
            fundingSource = context.fundingSourceId,
        )
        if (owners.isEmpty()) {
            throw NotFoundException(
                "Funding source has no owner: ${context.tenantId.value} / ${context.fundingSourceId.value}",
            )
        }

        val authorities = mandateResolver.resolve(
            tenantId = context.tenantId,
            actingPartyProfileId = context.actingPartyProfileId,
            fundingSourceId = context.fundingSourceId,
            owners = owners,
            at = at,
        )
        if (authorities.isEmpty()) {
            return result(
                context = context,
                currency = currency,
                at = at,
                status = OperationCapacityStatus.UNAVAILABLE,
                reasons = listOf(
                    OperationCapacityReasonResult(
                        code = "ACTOR_NOT_AUTHORIZED",
                        message = "Acting party neither owns the funding source nor has an applicable mandate",
                    ),
                ),
            )
        }

        val matchingRules = limitRuleMatcher.findMatches(
            context = context,
            authorities = authorities,
            references = references,
            at = at,
        )
        if (matchingRules.isEmpty()) {
            return noMatchingRuleResult(
                context = context,
                currency = currency,
                at = at,
                behavior = references.tenant.noMatchingRuleBehavior,
            )
        }

        val selectedRules = ApplicableLimitRuleSelector.select(matchingRules)
        val applicableLimits = selectedRules.map { it.toUsageResult(context, at) }
        // Unlimited rules are reported as applicable but never cap capacity.
        val boundedLimits = applicableLimits.filter { it.availableAmount != null }
        val currencyMismatches = selectedRules
            .map { it.rule }
            .filter { it.isBounded && it.amount.currency != currency }
            .map { rule ->
                OperationCapacityReasonResult(
                    code = "LIMIT_CURRENCY_MISMATCH",
                    message = "Cannot calculate ${currency.value} capacity against " +
                        "${rule.cap.currency.value} limit rule without currency conversion",
                    limitRuleId = rule.id.value,
                )
            }

        if (currencyMismatches.isNotEmpty()) {
            return result(
                context = context,
                currency = currency,
                at = at,
                status = OperationCapacityStatus.UNAVAILABLE,
                applicableLimits = applicableLimits,
                reasons = currencyMismatches,
            )
        }
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
            name = rule.name.value,
            description = rule.description,
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

    private fun noMatchingRuleResult(
        context: LimitEvaluationContext,
        currency: CurrencyCode,
        at: Instant,
        behavior: NoMatchingRuleBehavior,
    ): OperationCapacityResult =
        when (behavior) {
            NoMatchingRuleBehavior.ALLOW -> result(
                context = context,
                currency = currency,
                at = at,
                status = OperationCapacityStatus.UNLIMITED,
            )

            NoMatchingRuleBehavior.DENY -> result(
                context = context,
                currency = currency,
                at = at,
                status = OperationCapacityStatus.UNAVAILABLE,
                reasons = listOf(
                    OperationCapacityReasonResult(
                        code = "NO_MATCHING_LIMIT_RULE",
                        message = "No active limit rule applies to this operation",
                    ),
                ),
            )

            NoMatchingRuleBehavior.NOT_APPLICABLE -> result(
                context = context,
                currency = currency,
                at = at,
                status = OperationCapacityStatus.NOT_APPLICABLE,
            )
        }

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
