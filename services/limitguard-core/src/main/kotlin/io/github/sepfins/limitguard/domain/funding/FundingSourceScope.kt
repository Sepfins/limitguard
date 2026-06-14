package io.github.sepfins.limitguard.domain.funding

import io.github.sepfins.limitguard.domain.common.FundingSourceGroupId
import io.github.sepfins.limitguard.domain.common.FundingSourceId

sealed interface FundingSourceScope {
    data object Global : FundingSourceScope

    data class SourceSpecific(val fundingSourceId: FundingSourceId) : FundingSourceScope

    data class SourceGroupSpecific(val fundingSourceGroupId: FundingSourceGroupId) : FundingSourceScope
}