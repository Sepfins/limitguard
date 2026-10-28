package io.github.sepfins.limitguard.application.change

import io.github.sepfins.limitguard.api.LimitAmount
import io.github.sepfins.limitguard.api.LimitRuleChangeEventType
import io.github.sepfins.limitguard.api.LimitRuleChangeType
import io.github.sepfins.limitguard.api.TrustedChangeActorContext
import io.github.sepfins.limitguard.api.commands.ApplyDueLimitChangesCommand
import io.github.sepfins.limitguard.api.commands.LimitRuleChangeOperation
import io.github.sepfins.limitguard.api.commands.RelayLimitChangeEventsCommand
import io.github.sepfins.limitguard.api.results.LimitChangeEventMessage
import io.github.sepfins.limitguard.support.FixedClockProvider
import io.github.sepfins.limitguard.support.OperationSubtreeScenario
import io.github.sepfins.limitguard.support.PostgresFacade
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LimitChangeEventRelayTest {
    private val clock = FixedClockProvider(NOW)
    private val received = mutableListOf<LimitChangeEventMessage>()
    private var sinkFails = false

    @Test
    fun `a scheduled raise relays every step in order with its target and the applied amounts`() {
        val s = scenario(relay = true)
        val configuration = s.configuration(s.operation("TRANSFER", leaf = true))
        val rule = s.rule(configuration, LimitAmount.Bounded(BigDecimal("500")))
        relay(s)
        received.clear()

        val raise = scheduledRaise(s, configuration)

        assertEquals(4, relay(s))
        assertEquals(
            listOf(
                LimitRuleChangeEventType.REQUESTED,
                LimitRuleChangeEventType.APPROVED,
                LimitRuleChangeEventType.SCHEDULED,
                LimitRuleChangeEventType.APPLIED,
            ),
            received.map { it.eventType },
        )
        received.forEach {
            assertEquals(raise, it.changeRequestId)
            assertEquals(s.owner, it.targetOwnerPartyProfileId)
            assertEquals(configuration, it.configurationId)
            assertEquals(null, it.targetMandateId)
            assertEquals(rule, it.limitRuleId)
            assertEquals(LimitRuleChangeType.UPDATE, it.changeType)
            assertEquals(LimitAmount.Bounded(BigDecimal("800.0000")), it.requestedAmount)
            assertEquals("EUR", it.currency)
            assertEquals(hours(1), it.effectiveAt)
        }
        val applied = received.last()
        assertEquals(LimitAmount.Bounded(BigDecimal("500.0000")), applied.beforeAmount)
        assertEquals(LimitAmount.Bounded(BigDecimal("800.0000")), applied.afterAmount)
        assertEquals(hours(1), applied.occurredAt)
        assertEquals(0, relay(s))
    }

    @Test
    fun `a host that leaves relaying off relays nothing`() {
        val s = scenario(relay = false)
        scheduledRaise(s, s.configuration(s.operation("TRANSFER", leaf = true)))

        assertEquals(0, relay(s))
        assertEquals(emptyList(), received)
    }

    @Test
    fun `a failing sink leaves the batch queued for the next call`() {
        val s = scenario(relay = true)
        s.rule(s.configuration(s.operation("TRANSFER", leaf = true)), LimitAmount.Bounded(BigDecimal("500")))
        sinkFails = true

        assertFailsWith<IllegalStateException> { relay(s, limit = 2) }
        val attempted = received.toList()
        received.clear()
        sinkFails = false

        assertEquals(2, relay(s, limit = 2))
        assertEquals(attempted.map { it.eventId }, received.map { it.eventId })
    }

    private fun scenario(relay: Boolean) =
        OperationSubtreeScenario(PostgresFacade.create(clock, relayLimitChangeEvents = relay), NOW)

    private fun scheduledRaise(s: OperationSubtreeScenario, configuration: UUID): UUID {
        val command = s.changeCommand(configuration, LimitRuleChangeOperation.SetAmount(LimitAmount.Bounded(BigDecimal("800"))))
        val raise = s.facade.limitChangeService.requestLimitRuleChange(command.copy(effectiveAt = hours(1))).id
        s.approve(raise)
        clock.setTo(hours(1))
        s.facade.limitChangeService.applyDueLimitChanges(
            ApplyDueLimitChangesCommand(s.tenant), TrustedChangeActorContext.InternalSystem,
        )
        return raise
    }

    private fun relay(s: OperationSubtreeScenario, limit: Int = 100): Int =
        s.facade.limitChangeService.relayLimitChangeEvents(
            RelayLimitChangeEventsCommand(sink = { messages ->
                received += messages
                check(!sinkFails) { "broker unavailable" }
            }, limit = limit),
            TrustedChangeActorContext.InternalSystem,
        )

    private companion object {
        val NOW: Instant = Instant.parse("2026-06-26T12:00:00Z")

        fun hours(count: Long): Instant = NOW.plus(Duration.ofHours(count))
    }
}
