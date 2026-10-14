package io.github.sepfins.limitguard.domain.limit

import io.github.sepfins.limitguard.domain.common.EffectiveWindow
import io.github.sepfins.limitguard.domain.common.LimitConfigurationId
import io.github.sepfins.limitguard.domain.common.LimitRuleId
import io.github.sepfins.limitguard.domain.common.Metadata
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
    val amount: LimitAmount,
    val mandateScope: MandateScope,
    val configurationId: LimitConfigurationId,
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
        require(limitKind is LimitKind.Periodic || usageConsumptionMode == LimitUsageConsumptionMode.CASCADING) {
            "ISOLATED usage consumption is only valid for periodic rules"
        }
    }

    fun isEffectiveAt(instant: Instant): Boolean =
        isActive && effectiveWindow.contains(instant)

    fun validateAgainst(configuration: LimitConfiguration): LimitRule =
        validateAgainst(configuration, emptyList())

    /**
     * The selected configuration owns the rule's channel, operation, and priority.
     * Funding scope remains configuration-owned and is inherited during transaction matching.
     * [ancestorConfigurations] must be ordered from the immediate parent to the root.
     */
    fun validateAgainst(
        configuration: LimitConfiguration,
        ancestorConfigurations: List<LimitConfiguration>,
    ): LimitRule {
        require(configuration.tenantId == tenantId) {
            "configuration tenant must match rule tenant"
        }
        require(configuration.id == configurationId) {
            "configuration id must match rule configurationId"
        }
        require(configuration.limitKind == limitKind) {
            "configuration limit kind must match rule limit kind"
        }
        require(configuration.currency == amount.currency) {
            "configuration currency must match rule currency"
        }
        require(amount.fitsWithin(configuration.maxAssignableAmount)) {
            "rule amount must fit within configuration maxAssignableAmount; unlimited needs an unlimited maximum"
        }
        require(configuration.channelScope == channelScope) {
            "rule channel scope must match configuration channel scope"
        }
        require(configuration.operationScope == operationScope) {
            "rule operation scope must match configuration operation scope"
        }
        require(configuration.priority == priority) {
            "rule priority must match configuration priority"
        }
        require(configuration.isActive) {
            "configuration must be active"
        }
        require(configuration.effectiveWindow.contains(effectiveWindow)) {
            "rule effective window must be contained by configuration effective window"
        }

        var child = configuration
        ancestorConfigurations.forEach { parent ->
            child.validateAgainstParent(parent)
            require(parent.isActive) {
                "ancestor configuration must be active"
            }
            require(amount.fitsWithin(parent.maxAssignableAmount)) {
                "rule amount must fit within ancestor configuration maxAssignableAmount"
            }
            require(parent.effectiveWindow.contains(effectiveWindow)) {
                "rule effective window must be contained by ancestor configuration effective window"
            }
            child = parent
        }
        require(child.parentConfigurationId == null) {
            "complete configuration ancestry is required to validate parent caps"
        }
        return this
    }
}
