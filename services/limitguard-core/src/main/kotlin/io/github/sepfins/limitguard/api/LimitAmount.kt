package io.github.sepfins.limitguard.api

import java.math.BigDecimal

/** A granted or assignable amount in the surrounding currency: a bounded value, or explicitly unlimited. */
sealed interface LimitAmount {
    data class Bounded(val value: BigDecimal) : LimitAmount

    data object Unlimited : LimitAmount
}
