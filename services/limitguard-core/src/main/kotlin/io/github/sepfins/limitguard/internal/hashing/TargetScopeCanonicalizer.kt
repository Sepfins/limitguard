package io.github.sepfins.limitguard.internal.hashing

import io.github.sepfins.limitguard.domain.limit.LimitChannelScope
import io.github.sepfins.limitguard.domain.limit.LimitKind
import io.github.sepfins.limitguard.domain.limit.LimitOperationScope
import io.github.sepfins.limitguard.domain.limit.LimitRuleTargetScope
import io.github.sepfins.limitguard.domain.mandate.MandateScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json


internal object TargetScopeCanonicalizer : Canonicalizer<LimitRuleTargetScope> {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = true
        prettyPrint = false
    }

    override fun canonicalize(value: LimitRuleTargetScope): String =
        json.encodeToString(value.toCanonical())

    private fun LimitRuleTargetScope.toCanonical(): CanonicalLimitRuleTargetScope {
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

        return CanonicalLimitRuleTargetScope(
            owner = owner.value.toString(),
            mandateScope = canonicalMandateScope.scope,
            mandateId = canonicalMandateScope.mandateId,
            channelScope = canonicalChannelScope.scope,
            channelGroupId = canonicalChannelScope.channelGroupId,
            channelId = canonicalChannelScope.channelId,
            operationScope = canonicalOperationScope.scope,
            operationTypeId = canonicalOperationScope.operationTypeId,
            currency = currency.value,
            kind = canonicalKind.kind,
            period = canonicalKind.period
        )
    }
}

@Serializable
private data class CanonicalLimitRuleTargetScope(
    val channelGroupId: String?,
    val channelId: String?,
    val channelScope: String,
    val currency: String,
    val kind: String,
    val mandateId: String?,
    val mandateScope: String,
    val operationScope: String,
    val operationTypeId: String?,
    val owner: String,
    val period: String?
)

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
