package io.github.sepfins.limitguard.application.evaluation

import io.github.sepfins.limitguard.domain.limit.appliesToFundingSource
import io.github.sepfins.limitguard.domain.transaction.PaymentTransaction
import io.github.sepfins.limitguard.port.persistence.ChannelGroupMemberRepository
import io.github.sepfins.limitguard.port.persistence.LimitConfigurationRepository
import io.github.sepfins.limitguard.port.persistence.LimitRuleRepository

/** Finds the rules the actor may use, then keeps those whose configurations cover the operation. */
internal class LimitRuleMatcher(
    private val limitRuleRepository: LimitRuleRepository,
    private val channelGroupMemberRepository: ChannelGroupMemberRepository,
    private val limitConfigurationRepository: LimitConfigurationRepository,
) {
    fun findMatches(
        transaction: PaymentTransaction,
        authorities: List<ResolvedFundingAuthority>,
        references: ResolvedAuthorizationReferences,
    ): List<ApplicableLimitRule> = findMatches(
        context = transaction.toLimitEvaluationContext(),
        authorities = authorities,
        references = references,
    )

    fun findMatches(
        context: LimitEvaluationContext,
        authorities: List<ResolvedFundingAuthority>,
        references: ResolvedAuthorizationReferences,
    ): List<ApplicableLimitRule> {
        val rules = authorities
            .flatMap { authority ->
                limitRuleRepository.findActiveForAuthority(
                    tenantId = context.tenantId,
                    owner = authority.ownerPartyProfileId,
                    mandateIds = authority.mandateIds,
                )
            }
            .distinctBy { it.id }
        if (rules.isEmpty()) {
            return emptyList()
        }

        val containingChannelGroupIds = channelGroupMemberRepository.findGroupIdsContaining(
            tenantId = context.tenantId,
            channelId = context.channelId,
        )
        val operationTypePath = references.operationTypePath.map { it.id }
        // A configuration's activity and window apply to new grants, not to rules already granted under it.
        val coveringConfigurations = limitConfigurationRepository
            .findByIds(context.tenantId, rules.mapTo(hashSetOf()) { it.configurationId })
            .filter {
                it.channelScope.includes(
                    channelId = context.channelId,
                    containingGroupIds = containingChannelGroupIds,
                )
            }
            .filter { it.operationScope.includes(operationTypePath) }
            .filter { it.fundingScope.appliesToFundingSource(references.fundingSourceProductPath) }
            .associateBy { it.id }

        return rules
            .filter { it.configurationId in coveringConfigurations }
            .map { ApplicableLimitRule(it, coveringConfigurations.getValue(it.configurationId)) }
            .sortedWith(
                compareBy<ApplicableLimitRule> { it.configuration.priority.value }
                    .thenBy { it.rule.id.value },
            )
    }
}
