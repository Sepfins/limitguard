package io.github.sepfins.limitguard.application.change

import io.github.sepfins.limitguard.api.LimitAmount
import io.github.sepfins.limitguard.api.LimitRuleChangeEventType
import io.github.sepfins.limitguard.api.LimitRuleChangeStatus
import io.github.sepfins.limitguard.api.TrustedChangeActorContext
import io.github.sepfins.limitguard.api.commands.ApplyDueLimitChangesCommand
import io.github.sepfins.limitguard.api.commands.LimitRuleChangeOperation
import io.github.sepfins.limitguard.support.FixedClockProvider
import io.github.sepfins.limitguard.support.OperationSubtreeScenario
import io.github.sepfins.limitguard.support.PostgresFacade
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class DeadlineFailureTest {
    private val clock = FixedClockProvider(NOW)

    private val s = OperationSubtreeScenario(PostgresFacade.create(clock, emptyList()), NOW)
    private val transfer = s.operation("TRANSFER", leaf = true)

    @Test
    fun `the due pass fails open requests whose deadline has passed once and leaves terminal requests alone`() {
        val requested = request(deadline = hours(1))
        val scheduled = s.approve(request(effectiveAt = hours(1), deadline = hours(2))).id
        val applies = s.approve(request(effectiveAt = hours(1))).id
        val applied = s.approve(request(deadline = hours(1))).id
        val appliedBefore = s.details(applied)
        clock.setTo(hours(3))

        val result = applyDue()

        assertEquals(listOf(applies), result.appliedChangeRequestIds)
        assertEquals(listOf(requested, scheduled), result.failedChangeRequestIds)
        assertDeadlineFailed(requested, hours(3))
        assertDeadlineFailed(scheduled, hours(3))
        assertEquals(appliedBefore, s.details(applied))

        val failedBefore = listOf(requested, scheduled).map(s::details)
        clock.setTo(hours(4))
        assertEquals(0 to 0, applyDue().let { it.appliedCount to it.failedCount })
        assertEquals(failedBefore, listOf(requested, scheduled).map(s::details))
    }


    private fun request(effectiveAt: Instant? = null, deadline: Instant? = null): UUID {
        val command = s.changeCommand(s.configuration(transfer), LimitRuleChangeOperation.SetAmount(LimitAmount.Bounded(BigDecimal("500"))))
        return s.facade.limitChangeService.requestLimitRuleChange(command.copy(effectiveAt = effectiveAt, expiresAt = deadline)).id
    }

    private fun applyDue() = s.facade.limitChangeService.applyDueLimitChanges(
        ApplyDueLimitChangesCommand(s.tenant), TrustedChangeActorContext.InternalSystem,
    )

    private fun assertDeadlineFailed(request: UUID, at: Instant) {
        val details = s.details(request)
        assertEquals(LimitRuleChangeStatus.FAILED, details.request.status)
        assertEquals("limit change request deadline elapsed", details.request.decisionReason)
        assertEquals(at, details.request.updatedAt)
        assertEquals(LimitRuleChangeEventType.FAILED, details.events.last().eventType)
        assertEquals(1, details.events.count { it.eventType == LimitRuleChangeEventType.FAILED })
        assertEquals(emptyList(), details.revisions)
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-06-26T12:00:00Z")

        fun hours(count: Long): Instant = NOW.plus(Duration.ofHours(count))
    }
}
