package io.github.sepfins.limitguard.domain.limit

sealed interface LimitKind {
    data object PerTransaction : LimitKind

    data class Periodic(val period: LimitPeriod) : LimitKind
}
