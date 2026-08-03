package io.github.sepfins.limitguard.application.usage

import io.github.sepfins.limitguard.domain.limit.LimitViolation
import io.github.sepfins.limitguard.domain.transaction.PaymentTransaction

internal data class UsageReservationPlan(
    val transaction: PaymentTransaction,
    val candidates: List<UsageReservationCandidate>,
    val violations: List<LimitViolation>,
) {
    val allowed: Boolean
        get() = violations.isEmpty()
}
