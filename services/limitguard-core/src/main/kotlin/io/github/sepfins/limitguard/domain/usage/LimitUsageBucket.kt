package io.github.sepfins.limitguard.domain.usage

import io.github.sepfins.limitguard.domain.common.LimitRuleId
import io.github.sepfins.limitguard.domain.common.Money
import io.github.sepfins.limitguard.domain.common.PartyProfileId
import io.github.sepfins.limitguard.domain.common.TenantId
import io.github.sepfins.limitguard.domain.common.UsageBucketId
import io.github.sepfins.limitguard.domain.common.UsageWindow
import kotlin.time.Instant

// Derived per-period counter for one matched limit rule, one assignee, and one
// window. `currency` from the schema is carried by the Money amounts (which are
// non-negative, matching the reserved_amount/consumed_amount >= 0 checks).
data class LimitUsageBucket(
    val id: UsageBucketId,
    val tenantId: TenantId,
    val limitRuleId: LimitRuleId,
    val assigneePartyProfileId: PartyProfileId,
    val window: UsageWindow,
    val reservedAmount: Money,
    val consumedAmount: Money,
    val createdAt: Instant? = null,
    val updatedAt: Instant? = null
) {
    init {
        require(reservedAmount.currency == consumedAmount.currency) {
            "reservedAmount and consumedAmount must share the same currency"
        }
    }
}
