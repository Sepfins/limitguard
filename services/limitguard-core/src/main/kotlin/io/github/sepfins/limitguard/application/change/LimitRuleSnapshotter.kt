package io.github.sepfins.limitguard.application.change

import io.github.sepfins.limitguard.domain.common.Metadata
import io.github.sepfins.limitguard.domain.limit.LimitChannelScope
import io.github.sepfins.limitguard.domain.limit.LimitKind
import io.github.sepfins.limitguard.domain.limit.LimitOperationScope
import io.github.sepfins.limitguard.domain.limit.LimitRule
import io.github.sepfins.limitguard.domain.limit.LimitRuleTargetScope
import io.github.sepfins.limitguard.domain.mandate.MandateScope

internal object LimitRuleSnapshotter {
    fun ruleSnapshot(rule: LimitRule): Metadata =
        Metadata(
            mapOf(
                "id" to rule.id.value.toString(),
                "tenantId" to rule.tenantId.value.toString(),
                "ownerPartyProfileId" to rule.ownerPartyProfileId.value.toString(),
                "name" to rule.name.value,
                "description" to rule.description,
                "limitKind" to rule.limitKind.snapshotKind(),
                "period" to rule.limitKind.snapshotPeriod(),
                "amount" to rule.amount.amount.toString(),
                "currency" to rule.amount.currency.value,
                "mandateScope" to rule.mandateScope.snapshotScope(),
                "mandateId" to rule.mandateScope.snapshotMandateId(),
                "configurationId" to rule.configurationId?.value?.toString(),
                "channelScope" to rule.channelScope.snapshotScope(),
                "channelId" to rule.channelScope.snapshotChannelId(),
                "channelGroupId" to rule.channelScope.snapshotChannelGroupId(),
                "operationScope" to rule.operationScope.snapshotScope(),
                "operationTypeId" to rule.operationScope.snapshotOperationTypeId(),
                "usageConsumptionMode" to rule.usageConsumptionMode.name,
                "priority" to rule.priority.value,
                "isActive" to rule.isActive,
                "version" to rule.version.value,
                "startsAt" to rule.effectiveWindow.startsAt.toString(),
                "endsAt" to rule.effectiveWindow.endsAt?.toString(),
                "metadata" to rule.metadata.value,
            )
        )

    fun targetScopeSnapshot(targetScope: LimitRuleTargetScope): Metadata =
        Metadata(
            mapOf(
                "owner" to targetScope.owner.value.toString(),
                "currency" to targetScope.currency.value,
                "mandateScope" to targetScope.mandateScope.snapshotScope(),
                "mandateId" to targetScope.mandateScope.snapshotMandateId(),
                "channelScope" to targetScope.channelScope.snapshotScope(),
                "channelId" to targetScope.channelScope.snapshotChannelId(),
                "channelGroupId" to targetScope.channelScope.snapshotChannelGroupId(),
                "operationScope" to targetScope.operationScope.snapshotScope(),
                "operationTypeId" to targetScope.operationScope.snapshotOperationTypeId(),
                "kind" to targetScope.kind.snapshotKind(),
                "period" to targetScope.kind.snapshotPeriod(),
            )
        )

    private fun LimitKind.snapshotKind(): String =
        when (this) {
            LimitKind.PerTransaction -> "PER_TRANSACTION"
            is LimitKind.Periodic -> "PERIODIC"
        }

    private fun LimitKind.snapshotPeriod(): String? =
        when (this) {
            LimitKind.PerTransaction -> null
            is LimitKind.Periodic -> period.name
        }

    private fun MandateScope.snapshotScope(): String =
        when (this) {
            MandateScope.AllOwnerMandates -> "ALL_OWNER_MANDATES"
            is MandateScope.MandateSpecific -> "MANDATE"
        }

    private fun MandateScope.snapshotMandateId(): String? =
        when (this) {
            MandateScope.AllOwnerMandates -> null
            is MandateScope.MandateSpecific -> mandateId.value.toString()
        }

    private fun LimitChannelScope.snapshotScope(): String =
        when (this) {
            LimitChannelScope.AllChannels -> "ALL_CHANNELS"
            is LimitChannelScope.ChannelSpecific -> "CHANNEL"
            is LimitChannelScope.ChannelGroupSpecific -> "CHANNEL_GROUP"
        }

    private fun LimitChannelScope.snapshotChannelId(): String? =
        when (this) {
            LimitChannelScope.AllChannels -> null
            is LimitChannelScope.ChannelSpecific -> channelId.value.toString()
            is LimitChannelScope.ChannelGroupSpecific -> null
        }

    private fun LimitChannelScope.snapshotChannelGroupId(): String? =
        when (this) {
            LimitChannelScope.AllChannels -> null
            is LimitChannelScope.ChannelSpecific -> null
            is LimitChannelScope.ChannelGroupSpecific -> channelGroupId.value.toString()
        }

    private fun LimitOperationScope.snapshotScope(): String =
        when (this) {
            LimitOperationScope.AllOperations -> "ALL_OPERATIONS"
            is LimitOperationScope.OperationTypeSpecific -> "OPERATION_TYPE"
        }

    private fun LimitOperationScope.snapshotOperationTypeId(): String? =
        when (this) {
            LimitOperationScope.AllOperations -> null
            is LimitOperationScope.OperationTypeSpecific -> operationTypeId.value.toString()
        }
}
