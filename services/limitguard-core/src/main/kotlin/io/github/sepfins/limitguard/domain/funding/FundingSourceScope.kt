package io.github.sepfins.limitguard.domain.funding

import io.github.sepfins.limitguard.domain.common.FundingSourceGroupId
import io.github.sepfins.limitguard.domain.common.FundingSourceId

sealed interface FundingSourceScope {
    data object AllOwnerSources : FundingSourceScope

    data class FundingSourceSpecific(val fundingSourceId: FundingSourceId) : FundingSourceScope

    data class FundingSourceGroupSpecific(val fundingSourceGroupId: FundingSourceGroupId) : FundingSourceScope
}
