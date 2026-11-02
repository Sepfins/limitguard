package io.github.sepfins.limitguard.domain.change

import io.github.sepfins.limitguard.domain.common.CurrencyCode
import io.github.sepfins.limitguard.domain.common.LimitChangeRequestId
import io.github.sepfins.limitguard.domain.common.LimitConfigurationId
import io.github.sepfins.limitguard.domain.common.LimitRuleId
import io.github.sepfins.limitguard.domain.common.Money
import io.github.sepfins.limitguard.domain.common.PartyProfileId
import io.github.sepfins.limitguard.domain.common.TenantId
import io.github.sepfins.limitguard.domain.limit.LimitAmount
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LimitRuleChangeRequestTest {
    private val now = Instant.parse("2026-11-02T10:00:00Z")
    private val later = now.plusSeconds(3_600)

    @Test
    fun `a request is elapsed from its deadline, not before it`() {
        val request = request(expiresAt = later)

        assertFalse(request.isElapsedAt(later.minusMillis(1)))
        assertTrue(request.isElapsedAt(later))
        assertFalse(request(expiresAt = null).isElapsedAt(later))
    }

    @Test
    fun `an approved request is due once its effective time is reached or when it has none`() {
        val approved = request(effectiveAt = later).approve(reason = null, at = now)

        assertFalse(approved.isDueAt(now))
        assertTrue(approved.isDueAt(later))
        assertTrue(request().approve(reason = null, at = now).isDueAt(now))
    }

    @Test
    fun `a request awaiting approval is never due`() {
        assertTrue(request().isAwaitingApproval)
        assertFalse(request().isDueAt(later))
    }

    @Test
    fun `the application time is the effective time while it is ahead, else now`() {
        assertEquals(later, request(effectiveAt = later).applicationTimeFrom(now))
        assertEquals(later, request(effectiveAt = now).applicationTimeFrom(later))
        assertEquals(now, request().applicationTimeFrom(now))
    }

    @Test
    fun `approval leads to scheduling or application, and both close the request`() {
        val scheduled = request(effectiveAt = later).approve(reason = null, at = now).schedule(later, now)
        assertEquals(LimitRuleChangeStatus.PENDING_EFFECTIVE_DATE, scheduled.status)
        assertTrue(scheduled.status.isAwaitingApplication)

        val applied = scheduled.markApplied(LimitRuleId.of(UUID.randomUUID()), later)
        assertEquals(LimitRuleChangeStatus.APPLIED, applied.status)
        assertFalse(applied.status.isOpen)
        assertFailsWith<IllegalArgumentException> { applied.cancel(reason = null, at = later) }
    }

    @Test
    fun `a closed request cannot be decided again`() {
        val rejected = request().reject(reason = "no", at = now)

        assertEquals(LimitRuleChangeStatus.REJECTED, rejected.status)
        assertFalse(rejected.status.isOpen)
        assertFailsWith<IllegalArgumentException> { rejected.approve(reason = null, at = now) }
        assertFailsWith<IllegalArgumentException> { rejected.fail(reason = "late", at = now) }
    }

    @Test
    fun `an open request can fail from any open status`() {
        val approved = request(effectiveAt = later).approve(reason = null, at = now)

        assertEquals(LimitRuleChangeStatus.FAILED, request().fail("late", now).status)
        assertEquals(LimitRuleChangeStatus.FAILED, approved.fail("late", now).status)
        assertEquals(LimitRuleChangeStatus.FAILED, approved.schedule(later, now).fail("late", later).status)
    }

    private fun request(effectiveAt: Instant? = null, expiresAt: Instant? = null) =
        LimitRuleChangeRequest(
            id = LimitChangeRequestId.of(UUID.randomUUID()),
            tenantId = TenantId.of(UUID.randomUUID()),
            changeType = LimitRuleChangeType.CREATE,
            requestedBy = ChangeActor.System,
            targetOwnerPartyProfileId = PartyProfileId.of(UUID.randomUUID()),
            configurationId = LimitConfigurationId.of(UUID.randomUUID()),
            requestedAmount = LimitAmount.Bounded(Money.positive("100", CurrencyCode.of("EUR"))),
            requestActorContext = RequestActorContext.System(),
            effectiveAt = effectiveAt,
            expiresAt = expiresAt,
            createdAt = now,
            updatedAt = now,
        )
}
