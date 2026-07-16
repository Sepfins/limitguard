package io.github.sepfins.limitguard.domain.usage

import io.github.sepfins.limitguard.domain.common.CurrencyCode
import io.github.sepfins.limitguard.domain.common.LimitRuleId
import io.github.sepfins.limitguard.domain.common.Metadata
import io.github.sepfins.limitguard.domain.common.PartyProfileId
import io.github.sepfins.limitguard.domain.common.TenantId
import io.github.sepfins.limitguard.domain.common.TransactionEventId
import io.github.sepfins.limitguard.domain.common.UsageBucketId
import io.github.sepfins.limitguard.domain.common.UsageEventId
import java.math.BigDecimal
import java.time.Instant

// Per-rule audit trail of how a transaction event changed limit usage.
data class LimitUsageEvent(
    val id: UsageEventId,
    val tenantId: TenantId,
    val limitRuleId: LimitRuleId,
    val usageBucketId: UsageBucketId?,
    val transactionEventId: TransactionEventId,
    val assigneePartyProfileId: PartyProfileId,
    val eventType: LimitUsageEventType,
    // Signed change to bucket usage (RELEASE/REVERSAL are negative). Modeled as
    // BigDecimal + CurrencyCode rather than Money because Money is non-negative,
    // whereas the schema's amount_delta is signed (CHECK amount_delta <> 0).
    val amountDelta: BigDecimal,
    val currency: CurrencyCode,
    val metadata: Metadata = Metadata.EMPTY,
    val createdAt: Instant? = null
) {
    init {
        require(amountDelta.signum() != 0) { "amountDelta must be non-zero" }
    }
}
