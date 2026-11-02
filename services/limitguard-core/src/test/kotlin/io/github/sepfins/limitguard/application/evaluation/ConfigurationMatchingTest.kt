package io.github.sepfins.limitguard.application.evaluation

import io.github.sepfins.limitguard.api.AuthorizationDecision
import io.github.sepfins.limitguard.api.LimitAmount
import io.github.sepfins.limitguard.api.OperationCapacityStatus
import io.github.sepfins.limitguard.api.commands.CaptureTransactionCommand
import io.github.sepfins.limitguard.api.commands.CreateMandateCommand
import io.github.sepfins.limitguard.api.commands.LimitRuleChangeOperation
import io.github.sepfins.limitguard.api.commands.SetLimitConfigurationActiveCommand
import io.github.sepfins.limitguard.support.FixedClockProvider
import io.github.sepfins.limitguard.support.OperationSubtreeScenario
import io.github.sepfins.limitguard.support.PostgresFacade
import java.math.BigDecimal
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ConfigurationMatchingTest {
    @Test
    fun `nested configurations cascade by configuration priority until an isolated tier`() {
        for (isolated in listOf(false, true)) {
            val s = scenario()
            val transfer = s.operation("TRANSFER")
            val instant = s.operation("INSTANT", transfer, leaf = true)
            val allTransfers = s.configuration(transfer, priority = 100)
            val instantTransfers = s.configuration(instant, priority = 10, isolated = isolated, parent = allTransfers)
            val allRule = s.rule(allTransfers, bounded("500"))
            val instantRule = s.rule(instantTransfers, bounded("100"))
            // A deactivated configuration stops new grants, not rules already granted under it.
            s.facade.tenantCatalogService.setLimitConfigurationActive(
                SetLimitConfigurationActiveCommand(s.tenant, instantTransfers, isActive = false),
            )
            val expected = if (isolated) listOf(instantRule) else listOf(instantRule, allRule)

            val authorization = s.authorize(instant)

            assertEquals(AuthorizationDecision.ALLOWED, authorization.decision)
            assertEquals(expected, authorization.matchedRules.map { it.id })
            assertEquals(listOf(10, 100).take(expected.size), authorization.matchedRules.map { it.priority })
            assertEquals(BigDecimal("40.0000"), s.usage(instantRule).reservedAmount)
            assertEquals(BigDecimal(if (isolated) "0.0000" else "40.0000"), s.usage(allRule).reservedAmount)
            val capacity = s.capacity(instant)
            assertEquals(expected, capacity.applicableLimits.map { it.limitRuleId })
            assertEquals(BigDecimal("60.0000"), capacity.effectiveAvailableAmount)
            assertEquals(listOf(allRule), s.authorize(transfer).matchedRules.map { it.id })
        }
    }

    @Test
    fun `a mandate narrowed rule is an extra cap on the owner wide rule of the same configuration`() {
        val s = scenario()
        val transfer = s.operation("TRANSFER", leaf = true)
        val delegate = s.profile("Delegate")
        val mandate = s.facade.tenantCatalogService.createMandate(
            CreateMandateCommand(s.tenant, s.owner, delegate, "Household"),
        ).id
        val configuration = s.configuration(transfer)
        val ownerWide = s.rule(configuration, bounded("500"))
        val narrowed = s.rule(configuration, bounded("100"), mandate = mandate)

        val denied = s.authorize(transfer, "150", actor = delegate)

        assertEquals(AuthorizationDecision.DENIED, denied.decision)
        assertEquals(setOf(ownerWide, narrowed), denied.matchedRules.map { it.id }.toSet())
        assertEquals(listOf(narrowed), denied.violations.map { it.limitRuleId })
        val capacity = s.capacity(transfer, delegate)
        assertEquals(setOf(ownerWide, narrowed), capacity.applicableLimits.map { it.limitRuleId }.toSet())
        assertEquals(BigDecimal("100.0000"), capacity.effectiveAvailableAmount)
        assertEquals(listOf(narrowed), capacity.limitingRuleIds)
        val owner = s.authorize(transfer, "150")
        assertEquals(AuthorizationDecision.ALLOWED, owner.decision)
        assertEquals(listOf(ownerWide), owner.matchedRules.map { it.id })
    }

    @Test
    fun `an unlimited rule is reported by authorization and capacity but never denies`() {
        val s = scenario()
        val transfer = s.operation("TRANSFER", leaf = true)
        val unlimitedConfiguration = s.configuration(transfer, priority = 10, maximum = LimitAmount.Unlimited)
        val unlimited = s.rule(unlimitedConfiguration, LimitAmount.Unlimited)

        val authorization = s.authorize(transfer, "5000")

        assertEquals(AuthorizationDecision.ALLOWED, authorization.decision)
        assertEquals(listOf(LimitAmount.Unlimited), authorization.matchedRules.map { it.amount })
        val onlyUnlimited = s.capacity(transfer)
        assertEquals(OperationCapacityStatus.AVAILABLE, onlyUnlimited.status)
        assertNull(onlyUnlimited.effectiveAvailableAmount)
        assertEquals(listOf(unlimited), onlyUnlimited.applicableLimits.map { it.limitRuleId })
        assertEquals(LimitAmount.Unlimited, onlyUnlimited.applicableLimits.single().limitAmount)
        assertNull(onlyUnlimited.applicableLimits.single().availableAmount)

        val bounded = s.grant(transfer, amount = "300", priority = 20, perTransaction = true)
        val denied = s.authorize(transfer, "400")

        assertEquals(AuthorizationDecision.DENIED, denied.decision)
        assertEquals(listOf(unlimited, bounded), denied.matchedRules.map { it.id })
        assertEquals(listOf(bounded), denied.violations.map { it.limitRuleId })
        val capacity = s.capacity(transfer)
        assertEquals(listOf(unlimited, bounded), capacity.applicableLimits.map { it.limitRuleId })
        assertEquals(BigDecimal("300.0000"), capacity.effectiveAvailableAmount)
        assertEquals(listOf(bounded), capacity.limitingRuleIds)
    }

    @Test
    fun `a cap set on an unlimited periodic rule counts what the window already spent`() {
        val s = scenario()
        val transfer = s.operation("TRANSFER", leaf = true)
        val configuration = s.configuration(transfer, maximum = LimitAmount.Unlimited)
        val rule = s.rule(configuration, LimitAmount.Unlimited)
        fun setAmount(amount: LimitAmount, expectedVersion: Int) = s.approve(s.request(
            configuration, LimitRuleChangeOperation.SetAmount(amount), expectedVersion,
        ).id)

        val spent = s.authorize(transfer, "9000")
        s.facade.limitEvaluationService.captureTransaction(CaptureTransactionCommand(
            s.tenant, assertNotNull(spent.paymentTransactionId),
        ))
        s.authorize(transfer, "500")

        setAmount(bounded("10000"), expectedVersion = 1)
        val capped = s.usage(rule)
        assertEquals(BigDecimal("9000.0000"), capped.consumedAmount)
        assertEquals(BigDecimal("500.0000"), capped.reservedAmount)
        assertEquals(BigDecimal("500.0000"), capped.remainingAmount)
        assertEquals(AuthorizationDecision.DENIED, s.authorize(transfer, "501").decision)

        setAmount(LimitAmount.Unlimited, expectedVersion = 2)
        s.authorize(transfer, "4000")
        setAmount(bounded("15000"), expectedVersion = 3)
        assertEquals(BigDecimal("1500.0000"), s.usage(rule).remainingAmount)
    }

    @Test
    fun `an unlimited rule counts no usage for a payment in another currency`() {
        val s = scenario()
        val transfer = s.operation("TRANSFER", leaf = true)
        s.rule(s.configuration(transfer, maximum = LimitAmount.Unlimited), LimitAmount.Unlimited)

        val authorization = s.facade.limitEvaluationService.authorizeTransaction(
            s.command(transfer, "100").copy(currency = "USD"),
        )

        assertEquals(AuthorizationDecision.ALLOWED, authorization.decision)
        val capture = s.facade.limitEvaluationService.captureTransaction(CaptureTransactionCommand(
            s.tenant, assertNotNull(authorization.paymentTransactionId),
        ))
        assertEquals(emptyList(), capture.usageEvents)
    }

    private fun bounded(amount: String) = LimitAmount.Bounded(BigDecimal(amount))

    private fun scenario(): OperationSubtreeScenario {
        val now = Instant.parse("2026-10-05T10:00:00Z")
        return OperationSubtreeScenario(PostgresFacade.create(FixedClockProvider(now), emptyList()), now)
    }
}
