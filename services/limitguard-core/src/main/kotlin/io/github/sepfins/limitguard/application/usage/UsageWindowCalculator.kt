package io.github.sepfins.limitguard.application.usage

import io.github.sepfins.limitguard.domain.common.UsageWindow
import io.github.sepfins.limitguard.domain.limit.LimitPeriod
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

internal class UsageWindowCalculator(
    private val zoneId: ZoneId,
) {
    fun currentDate(now: Instant): LocalDate =
        now.atZone(zoneId).toLocalDate()

    fun calculateWindow(
        now: Instant,
        period: LimitPeriod,
    ): UsageWindow {
        val currentDate = currentDate(now)

        return when (period) {
            LimitPeriod.DAILY -> {
                val startDate = currentDate
                val endDate = startDate.plusDays(1)

                UsageWindow(
                    startDate.atStartOfDay(zoneId).toInstant(),
                    endDate.atStartOfDay(zoneId).toInstant(),
                )
            }

            LimitPeriod.MONTHLY -> {
                val startDate = currentDate.withDayOfMonth(1)
                val endDate = startDate.plusMonths(1)

                UsageWindow(
                    startDate.atStartOfDay(zoneId).toInstant(),
                    endDate.atStartOfDay(zoneId).toInstant(),
                )
            }
        }
    }
}
