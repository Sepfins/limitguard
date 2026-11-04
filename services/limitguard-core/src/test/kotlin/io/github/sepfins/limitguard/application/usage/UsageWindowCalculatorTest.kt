package io.github.sepfins.limitguard.application.usage

import io.github.sepfins.limitguard.domain.common.UsageWindow
import io.github.sepfins.limitguard.domain.limit.LimitPeriod
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

class UsageWindowCalculatorTest {
    private val calculator = UsageWindowCalculator(ZoneId.of("Europe/Brussels"))

    @Test
    fun `daily window starts at the bank's local midnight, not UTC`() {
        assertEquals(
            UsageWindow(
                periodStartsAt = Instant.parse("2026-07-04T22:00:00Z"),
                periodEndsAt = Instant.parse("2026-07-05T22:00:00Z"),
            ),
            calculator.calculateWindow(
                now = Instant.parse("2026-07-04T22:30:00Z"),
                period = LimitPeriod.DAILY,
            ),
        )
    }

    @Test
    fun `daily window runs 25 hours when the clocks go back`() {
        assertEquals(
            UsageWindow(
                periodStartsAt = Instant.parse("2026-10-24T22:00:00Z"),
                periodEndsAt = Instant.parse("2026-10-25T23:00:00Z"),
            ),
            calculator.calculateWindow(
                now = Instant.parse("2026-10-25T12:00:00Z"),
                period = LimitPeriod.DAILY,
            ),
        )
    }

    @Test
    fun `monthly window turns over at the bank's new year`() {
        assertEquals(
            UsageWindow(
                periodStartsAt = Instant.parse("2026-12-31T23:00:00Z"),
                periodEndsAt = Instant.parse("2027-01-31T23:00:00Z"),
            ),
            calculator.calculateWindow(
                now = Instant.parse("2026-12-31T23:30:00Z"),
                period = LimitPeriod.MONTHLY,
            ),
        )
    }
}
