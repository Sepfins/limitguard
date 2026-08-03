package io.github.sepfins.limitguard.domain.limit

import io.github.sepfins.limitguard.domain.common.EffectiveWindow
import io.github.sepfins.limitguard.domain.common.LimitConfigurationId
import io.github.sepfins.limitguard.domain.common.LimitRuleId
import io.github.sepfins.limitguard.domain.common.Metadata
import io.github.sepfins.limitguard.domain.common.Money
import io.github.sepfins.limitguard.domain.common.PartyProfileId
import io.github.sepfins.limitguard.domain.common.TenantId
import io.github.sepfins.limitguard.domain.common.Version
import io.github.sepfins.limitguard.domain.mandate.MandateScope
import java.time.Instant

data class LimitRule(
    val id: LimitRuleId,
    val tenantId: TenantId,
    val ownerPartyProfileId: PartyProfileId,
    val name: LimitRuleName,
    val description: String?,
    val limitKind: LimitKind,
    val amount: Money,
    val mandateScope: MandateScope,
    val configurationId: LimitConfigurationId?,
    val channelScope: LimitChannelScope,
    val operationScope: LimitOperationScope,
    val usageConsumptionMode: LimitUsageConsumptionMode,
    val priority: LimitPriority,
    val isActive: Boolean,
    val version: Version,
    val effectiveWindow: EffectiveWindow,
    val metadata: Metadata = Metadata.EMPTY,
    val createdAt: Instant? = null,
    val updatedAt: Instant? = null
) {
    init {
        require(amount.isPositive) {
            "amount must be positive"
        }
        require(limitKind is LimitKind.Periodic || usageConsumptionMode == LimitUsageConsumptionMode.CASCADING) {
            "ISOLATED usage consumption is only valid for periodic rules"
        }
    }

    fun isEffectiveAt(instant: Instant): Boolean =
        isActive && effectiveWindow.contains(instant)

    fun validateAgainst(configuration: LimitConfiguration): LimitRule {
        require(configuration.tenantId == tenantId) {
            "configuration tenant must match rule tenant"
        }
        if (configurationId != null) {
            require(configuration.id == configurationId) {
                "configuration id must match rule configurationId"
            }
        }
        require(configuration.limitKind == limitKind) {
            "configuration limit kind must match rule limit kind"
        }
        require(configuration.maxAssignableAmount.currency == amount.currency) {
            "configuration currency must match rule currency"
        }
        require(amount <= configuration.maxAssignableAmount) {
            "rule amount must be less than or equal to configuration maxAssignableAmount"
        }
        return this
    }
}
