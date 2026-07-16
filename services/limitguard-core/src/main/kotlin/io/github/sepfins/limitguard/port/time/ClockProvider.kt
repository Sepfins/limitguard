package io.github.sepfins.limitguard.port.time

import java.time.Instant

interface ClockProvider {
    fun now(): Instant
}