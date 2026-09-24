package io.github.sepfins.limitguard.domain.common

import java.time.Instant

data class EffectiveWindow(val startsAt: Instant, val endsAt: Instant?) {
    init {
        require(endsAt == null || startsAt < endsAt) { "startsAt must be before endsAt" }
    }

    fun contains(time: Instant): Boolean {
        return time >= startsAt && (endsAt == null || time < endsAt)
    }

    fun contains(other: EffectiveWindow): Boolean =
        other.startsAt >= startsAt &&
            (endsAt == null || (other.endsAt != null && other.endsAt <= endsAt))

    /** Half-open interval overlap: touching endpoints do not overlap. */
    fun overlaps(other: EffectiveWindow): Boolean =
        (other.endsAt == null || startsAt < other.endsAt) &&
            (endsAt == null || other.startsAt < endsAt)
}
