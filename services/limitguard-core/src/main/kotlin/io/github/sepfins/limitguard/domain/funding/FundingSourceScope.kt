package io.github.sepfins.limitguard.domain.funding

import io.github.sepfins.limitguard.domain.common.FundingSourceGroupId
import io.github.sepfins.limitguard.domain.common.FundingSourceId

sealed interface FundingSourceScope {
    data object AllOwnerSources : FundingSourceScope

    data class FundingSourceSpecific(val fundingSourceId: FundingSourceId) : FundingSourceScope

    data class FundingSourceGroupSpecific(val fundingSourceGroupId: FundingSourceGroupId) : FundingSourceScope

    fun includes(
        fundingSourceId: FundingSourceId,
        containingGroupIds: Set<FundingSourceGroupId>,
    ): Boolean =
        when (this) {
            AllOwnerSources -> true
            is FundingSourceSpecific -> this.fundingSourceId == fundingSourceId
            is FundingSourceGroupSpecific -> fundingSourceGroupId in containingGroupIds
        }
}
