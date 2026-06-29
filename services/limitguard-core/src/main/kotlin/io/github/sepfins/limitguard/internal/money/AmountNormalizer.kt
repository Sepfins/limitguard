package io.github.sepfins.limitguard.internal.money

import java.math.BigDecimal
import java.math.RoundingMode

internal object AmountNormalizer {
    const val SCALE: Int = 4
    const val PRECISION: Int = 20

    fun normalize(amount: BigDecimal): BigDecimal {
        val normalized = amount.setScale(SCALE, RoundingMode.HALF_UP)
        require(normalized.precision() <= PRECISION) {
            "Amount exceeds maximum precision of $PRECISION digits"
        }
        return normalized
    }
}
