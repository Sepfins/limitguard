package io.github.sepfins.limitguard.support

import io.github.sepfins.limitguard.port.time.ClockProvider
import kotlin.time.Duration
import kotlin.time.Instant

// A clock tests can freeze at a fixed instant and advance explicitly, to reach
// window, expiry, and period-rollover boundaries.
class FixedClockProvider(var instant: Instant) : ClockProvider {
    override fun now(): Instant = instant

    fun setTo(instant: Instant) {
        this.instant = instant
    }

    fun advanceBy(duration: Duration) {
        instant += duration
    }
}
