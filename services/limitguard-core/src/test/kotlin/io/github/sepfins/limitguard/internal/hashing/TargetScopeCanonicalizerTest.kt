package io.github.sepfins.limitguard.internal.hashing

import io.github.sepfins.limitguard.domain.common.CurrencyCode
import io.github.sepfins.limitguard.domain.common.FundingSourceProductId
import io.github.sepfins.limitguard.domain.common.PartyProfileId
import io.github.sepfins.limitguard.domain.limit.LimitChannelScope
import io.github.sepfins.limitguard.domain.limit.LimitConfigurationFundingScope
import io.github.sepfins.limitguard.domain.limit.LimitKind
import io.github.sepfins.limitguard.domain.limit.LimitOperationScope
import io.github.sepfins.limitguard.domain.limit.LimitRuleTargetScope
import io.github.sepfins.limitguard.domain.mandate.MandateScope
import io.github.sepfins.limitguard.domain.funding.FundingSourceCategory
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class TargetScopeCanonicalizerTest {
    @Test
    fun `funding selectors distinguish otherwise equal targets`() {
        val scope = LimitRuleTargetScope(
            owner = PartyProfileId.of(UUID(0, 1)), mandateScope = MandateScope.AllOwnerMandates,
            channelScope = LimitChannelScope.AllChannels, operationScope = LimitOperationScope.AllOperations,
            currency = CurrencyCode.of("EUR"), kind = LimitKind.PerTransaction,
            fundingScope = LimitConfigurationFundingScope.AllFundingSources,
        )
        val selectors = listOf(
            LimitConfigurationFundingScope.AllFundingSources,
            LimitConfigurationFundingScope.FundingSourceCategorySpecific(FundingSourceCategory.SAVINGS),
            LimitConfigurationFundingScope.FundingSourceCategorySpecific(FundingSourceCategory.TRANSACTIONAL),
            LimitConfigurationFundingScope.FundingSourceProductSpecific(FundingSourceProductId.of(UUID(0, 2))),
            LimitConfigurationFundingScope.FundingSourceProductSpecific(FundingSourceProductId.of(UUID(0, 3))),
        )
        assertEquals(5, selectors.map { TargetScopeCanonicalizer.hash(scope.copy(fundingScope = it)) }.toSet().size)
        assertEquals(5, selectors.map { TargetScopeCanonicalizer.snapshot(scope.copy(fundingScope = it)) }.toSet().size)
    }
}
