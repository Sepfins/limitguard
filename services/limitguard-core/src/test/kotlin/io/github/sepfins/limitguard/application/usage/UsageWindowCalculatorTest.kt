package io.github.sepfins.limitguard.application.usage

import io.github.sepfins.limitguard.domain.common.UsageWindow
import io.github.sepfins.limitguard.domain.limit.LimitPeriod
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class UsageWindowCalculatorTest {
    private val calculator = UsageWindowCalculator()

    @Test
    fun `daily window uses UTC start-inclusive end-exclusive boundaries`() {
        assertEquals(
            UsageWindow(
                periodStartsAt = Instant.parse("2026-07-04T00:00:00Z"),
                periodEndsAt = Instant.parse("2026-07-05T00:00:00Z"),
            ),
            calculator.calculateWindow(
                now = Instant.parse("2026-07-04T23:59:59.999Z"),
                period = LimitPeriod.DAILY,
            ),
        )
    }

    @Test
    fun `monthly window crosses year boundary in UTC`() {
        assertEquals(
            UsageWindow(
                periodStartsAt = Instant.parse("2026-12-01T00:00:00Z"),
                periodEndsAt = Instant.parse("2027-01-01T00:00:00Z"),
            ),
            calculator.calculateWindow(
                now = Instant.parse("2026-12-31T12:00:00Z"),
                period = LimitPeriod.MONTHLY,
            ),
        )
    }
}
