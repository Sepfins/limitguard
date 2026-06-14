package io.github.sepfins.limitguard.domain.limit

import io.github.sepfins.limitguard.domain.common.FundingSourceProductId
import io.github.sepfins.limitguard.domain.funding.FundingSourceCategory

sealed interface LimitConfigurationFundingScope {
    data object Global : LimitConfigurationFundingScope

    data class SourceCategorySpecific(val sourceCategory: FundingSourceCategory) : LimitConfigurationFundingScope

    data class ProductSpecific(val product: FundingSourceProductId) : LimitConfigurationFundingScope
}