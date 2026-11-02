package io.github.sepfins.limitguard.application.inquiry

import io.github.sepfins.limitguard.api.NoMatchingRuleBehavior
import io.github.sepfins.limitguard.api.OperationCapacityStatus
import io.github.sepfins.limitguard.api.commands.SetChannelActiveCommand
import io.github.sepfins.limitguard.api.queries.GetOperationCapacityQuery
import io.github.sepfins.limitguard.support.FixedClockProvider
import io.github.sepfins.limitguard.support.OperationSubtreeScenario
import io.github.sepfins.limitguard.support.PostgresFacade
import java.math.BigDecimal
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OperationCapacityTest {
    private val facade = PostgresFacade.create(FixedClockProvider(NOW), emptyList())

    @Test
    fun `capacity names why an operation is unavailable`() {
        val s = OperationSubtreeScenario(facade, NOW)
        val operation = s.operation("TRANSFER")
        val rule = s.grant(operation, amount = "100")

        val stranger = s.capacity(operation, actor = s.profile("Stranger"))
        assertEquals(OperationCapacityStatus.UNAVAILABLE, stranger.status)
        assertEquals("ACTOR_NOT_AUTHORIZED", stranger.reasons.single().code)
        assertEquals(emptyList(), stranger.applicableLimits)

        val foreign = facade.limitInquiryService.getOperationCapacity(GetOperationCapacityQuery(
            s.tenant, s.owner, s.source, operation, s.channel, "USD",
        ))
        assertEquals(OperationCapacityStatus.UNAVAILABLE, foreign.status)
        assertEquals("LIMIT_CURRENCY_MISMATCH", foreign.reasons.single().code)
        assertEquals(rule, foreign.reasons.single().limitRuleId)
        assertEquals(listOf(rule), foreign.applicableLimits.map { it.limitRuleId })

        facade.tenantCatalogService.setChannelActive(SetChannelActiveCommand(s.tenant, s.channel, false))
        val inactive = s.capacity(operation)
        assertEquals(OperationCapacityStatus.UNAVAILABLE, inactive.status)
        assertEquals("CHANNEL_INACTIVE", inactive.reasons.single().code)
    }

    @Test
    fun `capacity without rules follows the tenant behavior`() {
        for ((behavior, expected) in listOf(
            NoMatchingRuleBehavior.ALLOW to OperationCapacityStatus.UNLIMITED,
            NoMatchingRuleBehavior.DENY to OperationCapacityStatus.UNAVAILABLE,
            NoMatchingRuleBehavior.NOT_APPLICABLE to OperationCapacityStatus.NOT_APPLICABLE,
        )) {
            val s = OperationSubtreeScenario(facade, NOW, behavior)

            val result = s.capacity(s.operation("TRANSFER"))

            assertEquals(expected, result.status, behavior.name)
            assertNull(result.effectiveAvailableAmount, behavior.name)
            assertEquals(behavior == NoMatchingRuleBehavior.DENY, result.reasons.isNotEmpty(), behavior.name)
        }
    }

    @Test
    fun `capacity is exhausted once the cascade is fully reserved`() {
        val s = OperationSubtreeScenario(facade, NOW)
        val operation = s.operation("TRANSFER")
        val rule = s.grant(operation, amount = "100")
        s.authorize(operation, "100")

        val result = s.capacity(operation)

        assertEquals(OperationCapacityStatus.EXHAUSTED, result.status)
        assertEquals(BigDecimal("0.0000"), result.effectiveAvailableAmount)
        assertEquals(listOf(rule), result.limitingRuleIds)
        assertEquals(OperationCapacityStatus.EXHAUSTED, result.applicableLimits.single().status)
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-06-28T10:15:30Z")
    }
}
