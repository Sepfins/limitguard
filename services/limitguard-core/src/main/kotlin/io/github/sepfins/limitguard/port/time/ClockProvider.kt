package io.github.sepfins.limitguard.port.time

import kotlin.time.Instant

interface ClockProvider {
    fun now(): Instant
}