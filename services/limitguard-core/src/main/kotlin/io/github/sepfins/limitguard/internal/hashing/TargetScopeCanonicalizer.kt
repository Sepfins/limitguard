package io.github.sepfins.limitguard.internal.hashing

import io.github.sepfins.limitguard.domain.limit.LimitChannelScope
import io.github.sepfins.limitguard.domain.limit.LimitKind
import io.github.sepfins.limitguard.domain.limit.LimitOperationScope
import io.github.sepfins.limitguard.domain.limit.LimitRuleTargetScope
import io.github.sepfins.limitguard.domain.mandate.MandateScope

internal object TargetScopeCanonicalizer : Canonicalizer<LimitRuleTargetScope> {
    override fun canonicalize(value: LimitRuleTargetScope): String =
        CanonicalJson.encodeObject(value.toCanonicalValues())

    private fun LimitRuleTargetScope.toCanonicalValues(): Map<String, Any?> {
        val canonicalMandateScope = when (mandateScope) {
            MandateScope.AllOwnerMandates ->
                CanonicalMandateScope("ALL_OWNER_MANDATES", null)
            is MandateScope.MandateSpecific ->
                CanonicalMandateScope("MANDATE", mandateScope.mandateId.value.toString())
        }

        val canonicalChannelScope = when (channelScope) {
            LimitChannelScope.AllChannels ->
                CanonicalChannelScope("ALL_CHANNELS", null, null)
            is LimitChannelScope.ChannelGroupSpecific ->
                CanonicalChannelScope("CHANNEL_GROUP", channelScope.channelGroupId.value.toString(), null)
            is LimitChannelScope.ChannelSpecific ->
                CanonicalChannelScope("CHANNEL", null, channelScope.channelId.value.toString())
        }

        val canonicalOperationScope = when (operationScope) {
            LimitOperationScope.AllOperations ->
                CanonicalOperationScope("ALL_OPERATIONS", null)
            is LimitOperationScope.OperationTypeSpecific ->
                CanonicalOperationScope("OPERATION", operationScope.operationTypeId.value.toString())
        }

        val canonicalKind = when (kind) {
            LimitKind.PerTransaction ->
                CanonicalKind("PER_TRANSACTION", null)
            is LimitKind.Periodic ->
                CanonicalKind("PERIODIC", kind.period.name)
        }

        return mapOf(
            "owner" to owner.value.toString(),
            "mandateScope" to canonicalMandateScope.scope,
            "mandateId" to canonicalMandateScope.mandateId,
            "channelScope" to canonicalChannelScope.scope,
            "channelGroupId" to canonicalChannelScope.channelGroupId,
            "channelId" to canonicalChannelScope.channelId,
            "operationScope" to canonicalOperationScope.scope,
            "operationTypeId" to canonicalOperationScope.operationTypeId,
            "currency" to currency.value,
            "kind" to canonicalKind.kind,
            "period" to canonicalKind.period,
        )
    }
}

private data class CanonicalMandateScope(
    val scope: String,
    val mandateId: String?
)

private data class CanonicalChannelScope(
    val scope: String,
    val channelGroupId: String?,
    val channelId: String?
)

private data class CanonicalOperationScope(
    val scope: String,
    val operationTypeId: String?
)

private data class CanonicalKind(
    val kind: String,
    val period: String?
)
