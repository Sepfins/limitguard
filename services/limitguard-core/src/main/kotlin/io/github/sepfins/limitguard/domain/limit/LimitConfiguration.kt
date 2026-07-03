package io.github.sepfins.limitguard.domain.limit

import io.github.sepfins.limitguard.domain.common.LimitConfigurationId
import io.github.sepfins.limitguard.domain.common.EffectiveWindow
import io.github.sepfins.limitguard.domain.common.Metadata
import io.github.sepfins.limitguard.domain.common.Money
import io.github.sepfins.limitguard.domain.common.TenantId
import kotlin.time.Instant

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
}
