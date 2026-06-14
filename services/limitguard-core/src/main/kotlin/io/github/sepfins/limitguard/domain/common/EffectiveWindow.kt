package io.github.sepfins.limitguard.domain.common

import kotlin.time.Instant

data class EffectiveWindow(val startsAt: Instant, val endsAt: Instant?) {
    init {
        require(endsAt == null || startsAt < endsAt) { "startsAt must be before endsAt" }
    }

    fun contains(time: Instant): Boolean {
        return time >= startsAt && (endsAt == null || time < endsAt)
    }
}
