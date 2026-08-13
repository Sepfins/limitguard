package io.github.sepfins.limitguard.domain.common

import io.github.sepfins.limitguard.internal.money.AmountNormalizer
import io.github.sepfins.limitguard.internal.money.MoneyMath
import java.math.BigDecimal

@ConsistentCopyVisibility
data class Money private constructor(
    val amount: BigDecimal,
    val currency: CurrencyCode,
) : Comparable<Money> {
    val isPositive: Boolean
        get() = amount.signum() > 0
    val isZero: Boolean
        get() = amount.signum() == 0

    companion object {
        fun of(amount: BigDecimal, currency: CurrencyCode): Money {
            require(amount >= BigDecimal.ZERO) {
                "Amount must be non-negative"
            }
            return Money(AmountNormalizer.normalize(amount), currency)
        }

        fun of(amount: String, currency: CurrencyCode): Money = of(amount.toBigDecimal(), currency)

        fun positive(amount: BigDecimal, currency: CurrencyCode): Money {
            val money = of(amount, currency)
            require(money.isPositive) { "Amount must be positive" }
            return money
        }

        fun positive(amount: String, currency: CurrencyCode): Money = positive(amount.toBigDecimal(), currency)

        fun zero(currency: CurrencyCode): Money = of(BigDecimal.ZERO, currency)
    }

    operator fun plus(other: Money): Money = MoneyMath.add(this, other)

    operator fun plus(other: BigDecimal): Money = MoneyMath.plusDelta(this, other)

    operator fun minus(other: Money): Money = MoneyMath.subtract(this, other)

    override fun compareTo(other: Money): Int {
        MoneyMath.requireSameCurrency(this, other)
        return amount.compareTo(other.amount)
    }
}
