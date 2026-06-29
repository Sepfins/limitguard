package io.github.sepfins.limitguard.internal.money

import io.github.sepfins.limitguard.domain.common.Money
import java.math.BigDecimal

internal object MoneyMath {
    fun requireSameCurrency(left: Money, right: Money) {
        require(left.currency == right.currency) {
            "Money currencies must match"
        }
    }

    fun add(left: Money, right: Money): Money {
        requireSameCurrency(left, right)
        return Money.of(left.amount + right.amount, left.currency)
    }

    fun subtract(left: Money, right: Money): Money {
        requireSameCurrency(left, right)
        return Money.of(left.amount - right.amount, left.currency)
    }

    fun plusDelta(money: Money, delta: BigDecimal): Money =
        Money.of(money.amount + delta, money.currency)
}
