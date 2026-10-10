package io.github.sepfins.limitguard.domain.limit

import io.github.sepfins.limitguard.domain.common.LimitConfigurationId
import io.github.sepfins.limitguard.domain.common.EffectiveWindow
import io.github.sepfins.limitguard.domain.common.Metadata
import io.github.sepfins.limitguard.domain.common.Money
import io.github.sepfins.limitguard.domain.common.TenantId
import java.time.Instant

data class LimitConfiguration(
    val id: LimitConfigurationId,
    val tenantId: TenantId,
    val parentConfigurationId: LimitConfigurationId?,
    val code: LimitConfigurationCode,
    val name: LimitConfigurationName,
    val description: String?,
    val limitKind: LimitKind,
    val fundingScope: LimitConfigurationFundingScope,
    val channelScope: LimitChannelScope,
    val operationScope: LimitOperationScope,
    val maxAssignableAmount: Money,
    val defaultAmount: Money?,
    val priority: LimitPriority,
    val isActive: Boolean,
    val effectiveWindow: EffectiveWindow,
    val metadata: Metadata = Metadata.EMPTY,
    val createdAt: Instant? = null,
    val updatedAt: Instant? = null
) {
    init {
        require(maxAssignableAmount.isPositive) {
            "maxAssignableAmount must be positive"
        }
        if (defaultAmount != null) {
            require(defaultAmount.isPositive) {
                "defaultAmount must be positive"
            }
            require(defaultAmount.currency == maxAssignableAmount.currency) {
                "defaultAmount currency must match maxAssignableAmount currency"
            }
            require(defaultAmount.amount <= maxAssignableAmount.amount) {
                "defaultAmount must be less than or equal to maxAssignableAmount"
            }
        }
    }

    fun isEffectiveAt(instant: Instant): Boolean =
        isActive && effectiveWindow.contains(instant)

    fun validateAgainstParent(parent: LimitConfiguration): LimitConfiguration {
        require(parentConfigurationId == parent.id) {
            "parent configuration id must match parentConfigurationId"
        }
        require(parent.tenantId == tenantId) {
            "parent configuration tenant must match child tenant"
        }
        require(
            fundingScope != LimitConfigurationFundingScope.AllFundingSources ||
                parent.fundingScope == LimitConfigurationFundingScope.AllFundingSources
        ) {
            "all-funding child configuration cannot fit a narrower parent funding envelope"
        }
        require(channelScope != LimitChannelScope.AllChannels || parent.channelScope == LimitChannelScope.AllChannels) {
            "all-channel child configuration cannot fit a narrower parent channel envelope"
        }
        require(parent.limitKind == limitKind) {
            "child configuration limit kind must match parent limit kind"
        }
        require(parent.maxAssignableAmount.currency == maxAssignableAmount.currency) {
            "child configuration currency must match parent currency"
        }
        require(maxAssignableAmount <= parent.maxAssignableAmount) {
            "child configuration maxAssignableAmount must be less than or equal to parent maxAssignableAmount"
        }
        require(defaultAmount == null || defaultAmount <= parent.maxAssignableAmount) {
            "child configuration defaultAmount must be less than or equal to parent maxAssignableAmount"
        }
        require(parent.effectiveWindow.contains(effectiveWindow)) {
            "child configuration effective window must be contained by parent effective window"
        }
        return this
    }
}
