package io.github.sepfins.limitguard.internal.hashing

import io.github.sepfins.limitguard.domain.change.ScopeHash
import io.github.sepfins.limitguard.domain.common.Metadata
import io.github.sepfins.limitguard.domain.limit.LimitChannelScope
import io.github.sepfins.limitguard.domain.limit.LimitConfigurationFundingScope
import io.github.sepfins.limitguard.domain.limit.LimitKind
import io.github.sepfins.limitguard.domain.limit.LimitOperationScope
import io.github.sepfins.limitguard.domain.limit.LimitRuleTargetScope
import io.github.sepfins.limitguard.domain.mandate.MandateScope

internal object TargetScopeCanonicalizer {
    fun hash(scope: LimitRuleTargetScope): ScopeHash = hash(snapshot(scope))

    fun hash(snapshot: Metadata): ScopeHash = ScopeHash.of(StableJsonHasher.sha256Hex(CanonicalJson.encodeObject(snapshot.value)))

    fun canonicalize(scope: LimitRuleTargetScope): String = CanonicalJson.encodeObject(snapshot(scope).value)

    fun snapshot(scope: LimitRuleTargetScope): Metadata = Metadata(values(scope).filterValues { it != null })

    private fun values(scope: LimitRuleTargetScope): Map<String, Any?> = with(scope) {
        mapOf(
            "owner" to owner.value.toString(),
            "mandateScope" to if (mandateScope is MandateScope.MandateSpecific) "MANDATE" else "ALL_OWNER_MANDATES",
            "mandateId" to (mandateScope as? MandateScope.MandateSpecific)?.mandateId?.value?.toString(),
            "channelScope" to when (channelScope) {
                LimitChannelScope.AllChannels -> "ALL_CHANNELS"
                is LimitChannelScope.ChannelSpecific -> "CHANNEL"
                is LimitChannelScope.ChannelGroupSpecific -> "CHANNEL_GROUP"
            },
            "channelId" to (channelScope as? LimitChannelScope.ChannelSpecific)?.channelId?.value?.toString(),
            "channelGroupId" to (channelScope as? LimitChannelScope.ChannelGroupSpecific)?.channelGroupId?.value?.toString(),
            "operationScope" to if (operationScope is LimitOperationScope.OperationTypeSpecific) "OPERATION_TYPE" else "ALL_OPERATIONS",
            "operationTypeId" to (operationScope as? LimitOperationScope.OperationTypeSpecific)?.operationTypeId?.value?.toString(),
            "currency" to currency.value,
            "kind" to if (kind is LimitKind.Periodic) "PERIODIC" else "PER_TRANSACTION",
            "period" to (kind as? LimitKind.Periodic)?.period?.name,
        ) + when (val funding = fundingScope) {
            LimitConfigurationFundingScope.AllFundingSources -> mapOf("fundingScope" to "ALL_FUNDING_SOURCES")
            is LimitConfigurationFundingScope.FundingSourceCategorySpecific -> mapOf(
                "fundingScope" to "FUNDING_SOURCE_CATEGORY", "fundingSourceCategory" to funding.sourceCategory.name,
            )
            is LimitConfigurationFundingScope.FundingSourceProductSpecific -> mapOf(
                "fundingScope" to "FUNDING_SOURCE_PRODUCT", "fundingSourceProductId" to funding.productId.value.toString(),
            )
        }
    }
}
