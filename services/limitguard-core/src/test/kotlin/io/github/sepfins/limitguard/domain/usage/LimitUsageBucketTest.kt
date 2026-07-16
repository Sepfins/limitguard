package io.github.sepfins.limitguard.domain.usage

import io.github.sepfins.limitguard.domain.common.CurrencyCode
import io.github.sepfins.limitguard.domain.common.LimitRuleId
import io.github.sepfins.limitguard.domain.common.Money
import io.github.sepfins.limitguard.domain.common.PartyProfileId
import io.github.sepfins.limitguard.domain.common.TenantId
import io.github.sepfins.limitguard.domain.common.UsageBucketId
import io.github.sepfins.limitguard.domain.common.UsageWindow
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import java.time.Instant

class LimitUsageBucketTest {
    private val eur = CurrencyCode.of("EUR")

    @Test
    fun `reserve consume release and reverse keep usage non-negative`() {
        val bucket = emptyBucket()
            .reserve(Money.positive("40", eur))
            .consume(Money.positive("10", eur))
            .release(Money.positive("20", eur))
            .reverse(Money.positive("5", eur))

        assertEquals(Money.of("10", eur), bucket.reservedAmount)
        assertEquals(Money.of("5", eur), bucket.consumedAmount)

        assertFailsWith<IllegalArgumentException> {
            bucket.release(Money.positive("11", eur))
        }
    }

    @Test
    fun `remaining and exceed checks use reserved plus consumed`() {
        val bucket = emptyBucket()
            .reserve(Money.positive("40", eur))
            .consume(Money.positive("10", eur))

        assertEquals(Money.of("60", eur), bucket.remaining(Money.positive("100", eur)))
        assertFalse(bucket.wouldExceed(Money.positive("100", eur), Money.positive("60", eur)))
        assertTrue(bucket.wouldExceed(Money.positive("100", eur), Money.positive("61", eur)))
    }

    private fun emptyBucket(): LimitUsageBucket =
        LimitUsageBucket(
            id = UsageBucketId.of(UUID.fromString("00000000-0000-0000-0000-000000000201")),
            tenantId = TenantId.of(UUID.fromString("00000000-0000-0000-0000-000000000202")),
            limitRuleId = LimitRuleId.of(UUID.fromString("00000000-0000-0000-0000-000000000203")),
            assigneePartyProfileId = PartyProfileId.of(UUID.fromString("00000000-0000-0000-0000-000000000204")),
            window = UsageWindow(
                periodStartsAt = Instant.parse("2026-01-01T00:00:00Z"),
                periodEndsAt = Instant.parse("2026-01-02T00:00:00Z"),
            ),
            reservedAmount = Money.zero(eur),
            consumedAmount = Money.zero(eur),
        )
}
