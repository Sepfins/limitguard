package io.github.sepfins.limitguard.application.evaluation

import io.github.sepfins.limitguard.domain.limit.LimitKind
import io.github.sepfins.limitguard.domain.limit.LimitRule
import io.github.sepfins.limitguard.domain.transaction.PaymentTransaction
import io.github.sepfins.limitguard.port.persistence.ChannelGroupMemberRepository
import io.github.sepfins.limitguard.port.persistence.LimitRuleRepository
import java.time.Instant

internal class LimitRuleMatcher(
    private val limitRuleRepository: LimitRuleRepository,
    private val channelGroupMemberRepository: ChannelGroupMemberRepository,
) {
    fun findMatches(
        transaction: PaymentTransaction,
        authorities: List<ResolvedFundingAuthority>,
        at: Instant,
    ): List<LimitRule> {
        val containingChannelGroupIds = channelGroupMemberRepository.findGroupIdsContaining(
            tenantId = transaction.tenantId,
            channelId = transaction.channelId,
        )

        return authorities
            .asSequence()
            .flatMap { authority ->
                limitRuleRepository
                    .findActiveForOwner(
                        tenantId = transaction.tenantId,
                        owner = authority.ownerPartyProfileId,
                        at = at,
                    )
                    .asSequence()
                    .filter { rule -> rule.mandateScope.includes(authority.mandateIds) } //TODO: is it not skipping rules that owner put onto himself, since he does not have a mandate, hence the response is always false? || authrority.directOwnership might be needed
            }
            .distinctBy { it.id }
            .filter { it.limitKind == LimitKind.PerTransaction }
            .filter { it.amount.currency == transaction.authorizedAmount.currency } // TODO: i'm not sure it's the logic I want to apply. For instance, if the actor has limit for a "BONIFICO" of 1000€, it's enough to switch the bonifico to something like USD to just skip the limit; should there not instead be a currency conversion baked in somewhere?
            .filter {
                it.channelScope.includes(
                    channelId = transaction.channelId,
                    containingGroupIds = containingChannelGroupIds,
                )
            }
            .filter { it.operationScope.includes(transaction.operationTypeId) }
            .sortedWith(
                compareBy<LimitRule> { it.priority.value }
                    .thenBy { it.id.value },
            )
            .toList()
    }
}
