package io.github.sepfins.limitguard.domain.usage

import io.github.sepfins.limitguard.domain.common.LimitRuleId
import io.github.sepfins.limitguard.domain.common.Money
import io.github.sepfins.limitguard.domain.common.PartyProfileId
import io.github.sepfins.limitguard.domain.common.TenantId
import io.github.sepfins.limitguard.domain.common.UsageBucketId
import io.github.sepfins.limitguard.domain.common.UsageWindow
import java.math.BigDecimal
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

    val currency = reservedAmount.currency

    fun remaining(limitAmount: Money): Money {
        requireSameCurrency(limitAmount)
        val remainingAmount = limitAmount.amount - reservedAmount.amount - consumedAmount.amount
        return Money.of(remainingAmount.max(BigDecimal.ZERO), limitAmount.currency)
    }

    fun wouldExceed(limitAmount: Money, delta: Money): Boolean {
        requireSameCurrency(limitAmount)
        requireSameCurrency(delta)
        return reservedAmount.amount + consumedAmount.amount + delta.amount > limitAmount.amount
    }

    fun reserve(amount: Money): LimitUsageBucket {
        requireSameCurrency(amount)
        require(amount.isPositive) { "reserve amount must be positive" }
        return copy(reservedAmount = reservedAmount + amount)
    }

    fun consume(amount: Money): LimitUsageBucket {
        requireSameCurrency(amount)
        require(amount.isPositive) { "consume amount must be positive" }
        require(reservedAmount >= amount) {
            "consume amount must not exceed reserved amount"
        }
        return copy(
            reservedAmount = reservedAmount - amount,
            consumedAmount = consumedAmount + amount,
        )
    }

    fun release(amount: Money): LimitUsageBucket {
        requireSameCurrency(amount)
        require(amount.isPositive) { "release amount must be positive" }
        require(reservedAmount >= amount) {
            "release amount must not exceed reserved amount"
        }
        return copy(reservedAmount = reservedAmount - amount)
    }

    fun reverse(amount: Money): LimitUsageBucket {
        requireSameCurrency(amount)
        require(amount.isPositive) { "reverse amount must be positive" }
        require(consumedAmount >= amount) {
            "reverse amount must not exceed consumed amount"
        }
        return copy(consumedAmount = consumedAmount - amount)
    }

    private fun requireSameCurrency(amount: Money) {
        require(amount.currency == currency) {
            "amount currency must match bucket currency"
        }
    }
}
