package io.github.sepfins.limitguard.application.evaluation

import io.github.sepfins.limitguard.domain.common.FundingSourceId
import io.github.sepfins.limitguard.domain.common.PartyProfileId
import io.github.sepfins.limitguard.domain.common.TenantId
import io.github.sepfins.limitguard.domain.funding.FundingSourceOwner
import io.github.sepfins.limitguard.domain.funding.FundingSourceScope
import io.github.sepfins.limitguard.port.persistence.FundingSourceGroupMemberRepository
import io.github.sepfins.limitguard.port.persistence.MandateRepository
import java.time.Instant

internal class MandateResolver(
    private val mandateRepository: MandateRepository,
    private val fundingSourceGroupMemberRepository: FundingSourceGroupMemberRepository,
) {
    fun resolve(
        tenantId: TenantId,
        actingPartyProfileId: PartyProfileId,
        fundingSourceId: FundingSourceId,
        owners: List<FundingSourceOwner>,
        at: Instant,
    ): List<ResolvedFundingAuthority> {
        val activeMandatesByOwner by lazy {
            mandateRepository
                .findActiveForAssignee(tenantId, actingPartyProfileId, at)
                .groupBy { it.ownerPartyProfileId }
        }

        return owners
            .mapNotNull { ownership ->
                val ownerId = ownership.partyProfileId
                if (ownerId == actingPartyProfileId) {
                    return@mapNotNull ResolvedFundingAuthority(
                        ownerPartyProfileId = ownerId,
                        directOwnership = true,
                        mandateIds = emptySet(),
                    )
                }
                val ownerMandates = activeMandatesByOwner[ownerId].orEmpty()
                val containingGroupIds =
                    if (ownerMandates.any { it.fundingScope is FundingSourceScope.FundingSourceGroupSpecific }) {
                        fundingSourceGroupMemberRepository.findGroupIdsContaining(
                            tenantId = tenantId,
                            owner = ownerId,
                            fundingSourceId = fundingSourceId,
                        )
                    } else {
                        emptySet()
                    }
                val applicableMandateIds = ownerMandates
                    .filter { mandate ->
                        mandate.fundingScope.includes(
                            fundingSourceId = fundingSourceId,
                            containingGroupIds = containingGroupIds,
                        )
                    }
                    .mapTo(linkedSetOf()) { it.id }

                if (applicableMandateIds.isEmpty()) {
                    null
                } else {
                    ResolvedFundingAuthority(
                        ownerPartyProfileId = ownerId,
                        directOwnership = false,
                        mandateIds = applicableMandateIds,
                    )
                }
            }
            .sortedBy { it.ownerPartyProfileId.value }
    }
}
