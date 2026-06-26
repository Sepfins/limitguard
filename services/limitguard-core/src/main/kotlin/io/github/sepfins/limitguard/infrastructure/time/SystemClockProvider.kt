package io.github.sepfins.limitguard.infrastructure.time

import io.github.sepfins.limitguard.port.time.ClockProvider
import kotlin.time.Clock
import kotlin.time.Instant

class SystemClockProvider(private val clock: Clock = Clock.System) : ClockProvider {
    override fun now(): Instant = clock.now()
}
