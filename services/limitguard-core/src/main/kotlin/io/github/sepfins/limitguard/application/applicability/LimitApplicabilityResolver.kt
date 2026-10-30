package io.github.sepfins.limitguard.application.applicability

import io.github.sepfins.limitguard.api.errors.NotFoundException
import io.github.sepfins.limitguard.application.isBounded
import io.github.sepfins.limitguard.domain.common.CurrencyCode
import io.github.sepfins.limitguard.domain.limit.LimitRule
import io.github.sepfins.limitguard.domain.tenant.NoMatchingRuleBehavior
import io.github.sepfins.limitguard.port.persistence.FundingSourceOwnerRepository
import java.time.Instant

/** Resolves Limit Applicability for one operation context. Reads only; takes no locks and never reads the clock. */
internal class LimitApplicabilityResolver(
    private val referenceResolver: OperationReferenceResolver,
    private val fundingSourceOwnerRepository: FundingSourceOwnerRepository,
    private val mandateResolver: MandateResolver,
    private val limitRuleMatcher: LimitRuleMatcher,
) {
    fun resolve(
        context: LimitEvaluationContext,
        currency: CurrencyCode,
        at: Instant,
    ): LimitApplicability {
        val references = referenceResolver.resolve(context)
        val unavailableReferences = references.unavailableReferences()
        if (unavailableReferences.isNotEmpty()) {
            return LimitApplicability.Denied(unavailableReferences)
        }

        val owners = fundingSourceOwnerRepository.findByFundingSource(context.tenantId, context.fundingSourceId)
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
            return LimitApplicability.Denied(listOf(ACTOR_NOT_AUTHORIZED))
        }

        val matchedRules = limitRuleMatcher.findMatches(context, authorities, references)
        if (matchedRules.isEmpty()) {
            return when (references.tenant.noMatchingRuleBehavior) {
                NoMatchingRuleBehavior.ALLOW -> LimitApplicability.Unrestricted(authorities)
                NoMatchingRuleBehavior.DENY -> LimitApplicability.Denied(listOf(NO_MATCHING_RULE), authorities = authorities)
                NoMatchingRuleBehavior.NOT_APPLICABLE -> LimitApplicability.NotApplicable(authorities)
            }
        }

        val cascade = ApplicableLimitRuleSelector.select(matchedRules)
        // Unlimited rules have no currency to disagree with.
        val currencyMismatches = cascade
            .map { it.rule }
            .filter { it.isBounded && it.amount.currency != currency }
            .map { it.currencyMismatch(currency) }
        if (currencyMismatches.isNotEmpty()) {
            return LimitApplicability.Denied(currencyMismatches, cascade, authorities)
        }

        return LimitApplicability.Applicable(cascade, authorities)
    }

    private fun LimitRule.currencyMismatch(currency: CurrencyCode): ApplicabilityReason =
        ApplicabilityReason(
            code = "LIMIT_CURRENCY_MISMATCH",
            message = "Cannot evaluate ${currency.value} operation against " +
                "${amount.currency.value} limit rule without currency conversion",
            limitRuleId = id,
        )

    private companion object {
        val ACTOR_NOT_AUTHORIZED = ApplicabilityReason(
            code = "ACTOR_NOT_AUTHORIZED",
            message = "Acting party neither owns the funding source nor has an applicable mandate",
        )
        val NO_MATCHING_RULE = ApplicabilityReason(
            code = "NO_MATCHING_LIMIT_RULE",
            message = "No active limit rule applies to this operation",
        )
    }
}
