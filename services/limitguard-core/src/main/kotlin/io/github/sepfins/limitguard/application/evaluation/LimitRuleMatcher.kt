package io.github.sepfins.limitguard.application.evaluation

import io.github.sepfins.limitguard.api.errors.NotFoundException
import io.github.sepfins.limitguard.domain.limit.LimitRule
import io.github.sepfins.limitguard.domain.limit.appliesToFundingSource
import io.github.sepfins.limitguard.domain.transaction.PaymentTransaction
import io.github.sepfins.limitguard.port.persistence.ChannelGroupMemberRepository
import io.github.sepfins.limitguard.port.persistence.LimitConfigurationRepository
import io.github.sepfins.limitguard.port.persistence.LimitRuleRepository
import java.time.Instant

internal class LimitRuleMatcher(
    private val limitRuleRepository: LimitRuleRepository,
    private val channelGroupMemberRepository: ChannelGroupMemberRepository,
    private val limitConfigurationRepository: LimitConfigurationRepository,
) {
    fun findMatches(
        transaction: PaymentTransaction,
        authorities: List<ResolvedFundingAuthority>,
        references: ResolvedAuthorizationReferences,
        at: Instant,
    ): List<LimitRule> = findMatches(
        context = transaction.toLimitEvaluationContext(),
        authorities = authorities,
        references = references,
        at = at,
    )

    fun findMatches(
        context: LimitEvaluationContext,
        authorities: List<ResolvedFundingAuthority>,
        references: ResolvedAuthorizationReferences,
        at: Instant,
    ): List<LimitRule> {
        val containingChannelGroupIds = channelGroupMemberRepository.findGroupIdsContaining(
            tenantId = context.tenantId,
            channelId = context.channelId,
        )
        val operationTypePath = references.operationTypePath.map { it.id }

        return authorities
            .asSequence()
            .flatMap { authority ->
                limitRuleRepository
                    .findActiveForOwner(
                        tenantId = context.tenantId,
                        owner = authority.ownerPartyProfileId,
                        at = at,
                    )
                    .asSequence()
                    .filter { rule ->
                        rule.mandateScope.appliesTo(
                            directOwner = authority.directOwnership,
                            applicableMandateIds = authority.mandateIds,
                        )
                    }
            }
            .distinctBy { it.id }
            .filter {
                it.channelScope.includes(
                    channelId = context.channelId,
                    containingGroupIds = containingChannelGroupIds,
                )
            }
            .filter { it.operationScope.includes(operationTypePath) }
            .filter { it.appliesToFundingScope(context, references) }
            .sortedWith(
                compareBy<LimitRule> { it.priority.value }
                    .thenBy { it.id.value },
            )
            .toList()
    }

    private fun LimitRule.appliesToFundingScope(
        context: LimitEvaluationContext,
        references: ResolvedAuthorizationReferences,
    ): Boolean {
        val configurationId = configurationId ?: return true
        val configuration = limitConfigurationRepository.findById(context.tenantId, configurationId)
            ?: throw NotFoundException("limit configuration not found: ${configurationId.value}")
        return configuration.fundingScope.appliesToFundingSource(references.fundingSourceProductPath)
    }
}
