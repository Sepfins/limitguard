package io.github.sepfins.limitguard.domain.limit

import io.github.sepfins.limitguard.domain.common.FundingSourceProductId
import io.github.sepfins.limitguard.domain.funding.FundingSourceCategory

sealed interface LimitConfigurationFundingScope {
    data object AllFundingSources : LimitConfigurationFundingScope

    data class FundingSourceCategorySpecific(val sourceCategory: FundingSourceCategory) : LimitConfigurationFundingScope

    data class FundingSourceProductSpecific(val productId: FundingSourceProductId) : LimitConfigurationFundingScope
}
