package io.github.sepfins.limitguard.application.evaluation

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
