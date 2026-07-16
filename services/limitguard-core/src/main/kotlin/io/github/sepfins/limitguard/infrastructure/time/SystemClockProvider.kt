package io.github.sepfins.limitguard.infrastructure.time

import io.github.sepfins.limitguard.port.time.ClockProvider
import java.time.Clock
import java.time.Instant

class SystemClockProvider(private val clock: Clock = Clock.systemUTC()) : ClockProvider {
    override fun now(): Instant = clock.instant()
}
