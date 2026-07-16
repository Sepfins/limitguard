package io.github.sepfins.limitguard.domain.common

import java.time.Instant

data class UsageWindow(val periodStartsAt: Instant, val periodEndsAt: Instant) {
    init {
        require(periodStartsAt < periodEndsAt) { "periodStartsAt must be before periodEndsAt" }
    }
}
