package io.github.sepfins.limitguard.domain.limit

import io.github.sepfins.limitguard.domain.common.CurrencyCode
import io.github.sepfins.limitguard.domain.common.Money

/**
 * A granted or assignable amount in one currency. Unlimited is an explicit value, never a
 * sentinel number; it still names its currency, so the amount stays the only carrier of
 * currency in the model.
 */
sealed interface LimitAmount {
    val currency: CurrencyCode

    data class Bounded(val money: Money) : LimitAmount {
        init {
            require(money.isPositive) { "bounded limit amount must be positive" }
        }

        override val currency: CurrencyCode get() = money.currency
    }

    data class Unlimited(override val currency: CurrencyCode) : LimitAmount

    /** A bounded amount fits any maximum of the same currency it does not exceed; Unlimited fits only an Unlimited maximum. */
    fun fitsWithin(maximum: LimitAmount): Boolean =
        currency == maximum.currency &&
            when (maximum) {
                is Unlimited -> true
                is Bounded -> this is Bounded && money <= maximum.money
            }
}
