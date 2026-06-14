package io.github.sepfins.limitguard.domain.common

import java.math.BigDecimal
import java.math.RoundingMode

data class Money private constructor(val amount: BigDecimal, val currency: CurrencyCode) {
    companion object{
        private const val SCALE = 4
        private const val PRECISION = 20

        fun of(amount: BigDecimal, currency: CurrencyCode): Money {
            require(amount >= BigDecimal.ZERO) {
                "Amount must be non-negative"
            }

            val normalized = amount.setScale(SCALE, RoundingMode.HALF_EVEN)

            require(normalized.precision() <= PRECISION) {
                "Amount exceeds maximum precision of $PRECISION digits"
            }

            return Money(normalized, currency)
        }

        fun of(amount: String, currency: CurrencyCode): Money = of(amount.toBigDecimal(), currency)
    }
}
