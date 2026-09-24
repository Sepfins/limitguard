package io.github.sepfins.limitguard.domain.limit

import io.github.sepfins.limitguard.domain.common.FundingSourceProductId
import io.github.sepfins.limitguard.domain.funding.FundingSourceCategory
import io.github.sepfins.limitguard.domain.funding.FundingSourceProduct

sealed interface LimitConfigurationFundingScope {
    data object AllFundingSources : LimitConfigurationFundingScope

    data class FundingSourceCategorySpecific(val sourceCategory: FundingSourceCategory) : LimitConfigurationFundingScope

    data class FundingSourceProductSpecific(val productId: FundingSourceProductId) : LimitConfigurationFundingScope
}

/**
 * Product paths are resolved leaf-first, so a node scope also applies to every descendant.
 */
internal fun LimitConfigurationFundingScope.appliesToFundingSource(
    sourceProductPath: List<FundingSourceProduct>,
): Boolean = when (this) {
    LimitConfigurationFundingScope.AllFundingSources -> true
    is LimitConfigurationFundingScope.FundingSourceCategorySpecific ->
        sourceProductPath.firstOrNull()?.accountCategory == sourceCategory
    is LimitConfigurationFundingScope.FundingSourceProductSpecific ->
        sourceProductPath.any { it.id == productId }
}
