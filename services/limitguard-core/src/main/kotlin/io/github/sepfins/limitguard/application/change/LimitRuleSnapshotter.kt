package io.github.sepfins.limitguard.application.change

import io.github.sepfins.limitguard.domain.common.ChannelGroupId
import io.github.sepfins.limitguard.domain.common.ChannelId
import io.github.sepfins.limitguard.domain.common.CurrencyCode
import io.github.sepfins.limitguard.domain.common.EffectiveWindow
import io.github.sepfins.limitguard.domain.common.LimitConfigurationId
import io.github.sepfins.limitguard.domain.common.LimitRuleId
import io.github.sepfins.limitguard.domain.common.MandateId
import io.github.sepfins.limitguard.domain.common.Metadata
import io.github.sepfins.limitguard.domain.common.Money
import io.github.sepfins.limitguard.domain.common.OperationTypeId
import io.github.sepfins.limitguard.domain.common.PartyProfileId
import io.github.sepfins.limitguard.domain.common.TenantId
import io.github.sepfins.limitguard.domain.common.Version
import io.github.sepfins.limitguard.domain.limit.LimitChannelScope
import io.github.sepfins.limitguard.domain.limit.LimitKind
import io.github.sepfins.limitguard.domain.limit.LimitOperationScope
import io.github.sepfins.limitguard.domain.limit.LimitPeriod
import io.github.sepfins.limitguard.domain.limit.LimitPriority
import io.github.sepfins.limitguard.domain.limit.LimitRule
import io.github.sepfins.limitguard.domain.limit.LimitRuleName
import io.github.sepfins.limitguard.domain.limit.LimitUsageConsumptionMode
import io.github.sepfins.limitguard.domain.mandate.MandateScope
import java.math.BigDecimal
import java.time.Instant

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

    fun restoreRule(
        snapshot: Metadata,
        createdAt: Instant? = null,
        updatedAt: Instant? = null,
    ): LimitRule {
        val values = snapshot.value
        return LimitRule(
            id = LimitRuleId.of(values.requiredString("id")),
            tenantId = TenantId.of(values.requiredString("tenantId")),
            ownerPartyProfileId = PartyProfileId.of(values.requiredString("ownerPartyProfileId")),
            name = LimitRuleName.of(values.requiredString("name")),
            description = values.optionalString("description"),
            limitKind = when (values.requiredString("limitKind")) {
                "PER_TRANSACTION" -> LimitKind.PerTransaction
                "PERIODIC" -> LimitKind.Periodic(
                    LimitPeriod.valueOf(values.requiredString("period"))
                )
                else -> throw IllegalArgumentException("unsupported limitKind in rule snapshot")
            },
            amount = Money.positive(
                values.requiredString("amount"),
                CurrencyCode.of(values.requiredString("currency")),
            ),
            mandateScope = when (values.requiredString("mandateScope")) {
                "ALL_OWNER_MANDATES" -> MandateScope.AllOwnerMandates
                "MANDATE" -> MandateScope.MandateSpecific(
                    MandateId.of(values.requiredString("mandateId"))
                )
                else -> throw IllegalArgumentException("unsupported mandateScope in rule snapshot")
            },
            configurationId = values.optionalString("configurationId")?.let { LimitConfigurationId.of(it) },
            channelScope = when (values.requiredString("channelScope")) {
                "ALL_CHANNELS" -> LimitChannelScope.AllChannels
                "CHANNEL" -> LimitChannelScope.ChannelSpecific(
                    ChannelId.of(values.requiredString("channelId"))
                )
                "CHANNEL_GROUP" -> LimitChannelScope.ChannelGroupSpecific(
                    ChannelGroupId.of(values.requiredString("channelGroupId"))
                )
                else -> throw IllegalArgumentException("unsupported channelScope in rule snapshot")
            },
            operationScope = when (values.requiredString("operationScope")) {
                "ALL_OPERATIONS" -> LimitOperationScope.AllOperations
                "OPERATION_TYPE" -> LimitOperationScope.OperationTypeSpecific(
                    OperationTypeId.of(values.requiredString("operationTypeId"))
                )
                else -> throw IllegalArgumentException("unsupported operationScope in rule snapshot")
            },
            usageConsumptionMode = LimitUsageConsumptionMode.valueOf(
                values.requiredString("usageConsumptionMode")
            ),
            priority = LimitPriority.of(values.requiredInt("priority")),
            isActive = values.requiredBoolean("isActive"),
            version = version(snapshot),
            effectiveWindow = EffectiveWindow(
                startsAt = values.requiredInstant("startsAt"),
                endsAt = values.optionalInstant("endsAt"),
            ),
            metadata = Metadata(values.requiredMap("metadata")),
            createdAt = createdAt,
            updatedAt = updatedAt,
        )
    }

    fun version(snapshot: Metadata): Version = Version.of(snapshot.value.requiredInt("version"))

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

    private fun Map<String, Any?>.requiredString(key: String): String =
        this[key] as? String
            ?: throw IllegalArgumentException("$key is required in rule snapshot")

    private fun Map<String, Any?>.optionalString(key: String): String? {
        val value = this[key] ?: return null
        return value as? String
            ?: throw IllegalArgumentException("$key must be a string in rule snapshot")
    }

    private fun Map<String, Any?>.requiredInt(key: String): Int {
        val value = this[key] as? Number
            ?: throw IllegalArgumentException("$key is required in rule snapshot")
        return try {
            BigDecimal(value.toString()).intValueExact()
        } catch (exception: NumberFormatException) {
            throw IllegalArgumentException("$key must be an integer in rule snapshot", exception)
        } catch (exception: ArithmeticException) {
            throw IllegalArgumentException("$key must be an integer in rule snapshot", exception)
        }
    }

    private fun Map<String, Any?>.requiredBoolean(key: String): Boolean =
        this[key] as? Boolean
            ?: throw IllegalArgumentException("$key is required in rule snapshot")

    private fun Map<String, Any?>.requiredInstant(key: String): Instant =
        try {
            Instant.parse(requiredString(key))
        } catch (exception: RuntimeException) {
            throw IllegalArgumentException("$key must be an instant in rule snapshot", exception)
        }

    private fun Map<String, Any?>.optionalInstant(key: String): Instant? {
        val value = optionalString(key) ?: return null
        return try {
            Instant.parse(value)
        } catch (exception: RuntimeException) {
            throw IllegalArgumentException("$key must be an instant in rule snapshot", exception)
        }
    }

    private fun Map<String, Any?>.requiredMap(key: String): Map<String, Any?> {
        val value = this[key] as? Map<*, *>
            ?: throw IllegalArgumentException("$key is required in rule snapshot")
        require(value.keys.all { it is String }) { "$key must contain string keys in rule snapshot" }
        return value.entries.associate { (entryKey, entryValue) -> entryKey as String to entryValue }
    }
}
