package io.github.sepfins.limitguard.application.evaluation

import io.github.sepfins.limitguard.api.AuthorizationDecision
import io.github.sepfins.limitguard.api.LimitUsageEventType
import io.github.sepfins.limitguard.api.NoMatchingRuleBehavior
import io.github.sepfins.limitguard.api.TransactionEventType
import io.github.sepfins.limitguard.api.TransactionStatus
import io.github.sepfins.limitguard.api.commands.CaptureTransactionCommand
import io.github.sepfins.limitguard.api.commands.ExpireDueAuthorizationsCommand
import io.github.sepfins.limitguard.api.commands.ReleaseAuthorizationCommand
import io.github.sepfins.limitguard.api.commands.ReverseTransactionCommand
import io.github.sepfins.limitguard.api.commands.SetChannelActiveCommand
import io.github.sepfins.limitguard.api.commands.SetFundingSourceActiveCommand
import io.github.sepfins.limitguard.api.commands.SetFundingSourceProductActiveCommand
import io.github.sepfins.limitguard.api.errors.AuthorizationExpiredException
import io.github.sepfins.limitguard.api.errors.IdempotencyConflictException
import io.github.sepfins.limitguard.api.errors.NotFoundException
import io.github.sepfins.limitguard.api.errors.ValidationException
import io.github.sepfins.limitguard.api.queries.GetTransactionHistoryQuery
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PaymentLifecycleTest {
    private val clock = FixedClockProvider(NOW)

    @Test
    fun `capture moves periodic usage from reserved to consumed`() {
        val s = scenario()
        val operation = s.operation("TRANSFER")
        val rule = s.grant(operation, amount = "100")
        val payment = payment(s, operation, "25")
        assertEquals(BigDecimal("25.0000"), s.usage(rule).reservedAmount)

        val capture = s.facade.limitEvaluationService.captureTransaction(CaptureTransactionCommand(
            s.tenant, payment, reason = " settlement ", metadata = mapOf("processorReference" to "capture-001"),
        ))

        assertEquals(TransactionStatus.CAPTURED, capture.status)
        assertEquals(BigDecimal("25.0000"), capture.amount)
        assertEquals(LimitUsageEventType.CONSUME, capture.usageEvents.single().eventType)
        val history = history(s, payment)
        assertEquals(BigDecimal("25.0000"), history.transaction.capturedAmount)
        val event = history.events.single { it.eventType == TransactionEventType.CAPTURE }
        assertEquals("settlement", event.reason)
        assertEquals(mapOf("processorReference" to "capture-001"), event.metadata)
        assertEquals(BigDecimal("0.0000"), s.usage(rule).reservedAmount)
        assertEquals(BigDecimal("25.0000"), s.usage(rule).consumedAmount)
    }

    @Test
    fun `release voids the uncaptured authorization and replays by key`() {
        val s = scenario()
        val operation = s.operation("TRANSFER")
        val rule = s.grant(operation, amount = "100")
        val payment = payment(s, operation, "40")
        val release = ReleaseAuthorizationCommand(
            s.tenant, payment, idempotencyKey = "release-1",
            metadata = mapOf("nested" to mapOf("amounts" to listOf(1L, BigDecimal("0.12345678901234567890")))),
        )

        val first = s.facade.limitEvaluationService.releaseAuthorization(release)

        assertEquals(first, s.facade.limitEvaluationService.releaseAuthorization(release))
        assertFailsWith<IdempotencyConflictException> {
            s.facade.limitEvaluationService.releaseAuthorization(release.copy(metadata = mapOf("attempt" to 2L)))
        }
        assertEquals(TransactionStatus.VOIDED, first.status)
        assertEquals(BigDecimal("-40.0000"), first.usageEvents.single().amountDelta)
        assertEquals(TransactionStatus.VOIDED, history(s, payment).transaction.status)
        assertEquals(BigDecimal("0.0000"), s.usage(rule).reservedAmount)
        assertEquals(BigDecimal("0.0000"), s.usage(rule).consumedAmount)
    }

    @Test
    fun `release after partial capture only releases the remaining reservation`() {
        val s = scenario()
        val operation = s.operation("TRANSFER")
        val rule = s.grant(operation, amount = "100")
        val payment = payment(s, operation, "60")
        s.facade.limitEvaluationService.captureTransaction(CaptureTransactionCommand(s.tenant, payment, BigDecimal("20")))

        val release = s.facade.limitEvaluationService.releaseAuthorization(ReleaseAuthorizationCommand(s.tenant, payment))

        assertEquals(TransactionStatus.CAPTURED, release.status)
        assertEquals(BigDecimal("40.0000"), release.amount)
        assertEquals(TransactionEventType.ADJUSTMENT, history(s, payment).events.last().eventType)
        assertEquals(BigDecimal("0.0000"), s.usage(rule).reservedAmount)
        assertEquals(BigDecimal("20.0000"), s.usage(rule).consumedAmount)
    }

    @Test
    fun `partial capture replays its result by key and conflicts on changed intent`() {
        val s = scenario()
        val operation = s.operation("TRANSFER")
        s.grant(operation, amount = "100")
        val payment = payment(s, operation, "75")
        val capture = CaptureTransactionCommand(
            s.tenant, payment, BigDecimal("1.00001"), "first presentment", "capture-1",
            mapOf("processorReference" to "capture-002"),
        )

        val first = s.facade.limitEvaluationService.captureTransaction(capture)

        assertEquals(first, s.facade.limitEvaluationService.captureTransaction(capture))
        assertEquals(TransactionStatus.PARTIALLY_CAPTURED, first.status)
        assertEquals(BigDecimal("1.0000"), first.amount)
        assertEquals(2, history(s, payment).events.size)
        for (changed in listOf(
            capture.copy(amount = BigDecimal("26")), capture.copy(amount = null),
            capture.copy(reason = "second presentment"), capture.copy(metadata = mapOf("processorReference" to "capture-003")),
        )) {
            assertFailsWith<IdempotencyConflictException> { s.facade.limitEvaluationService.captureTransaction(changed) }
        }
    }

    @Test
    fun `reversal reduces consumed usage until the payment is fully reversed`() {
        val s = scenario()
        val operation = s.operation("TRANSFER")
        val rule = s.grant(operation, amount = "100")
        val payment = payment(s, operation, "60")
        s.facade.limitEvaluationService.captureTransaction(CaptureTransactionCommand(s.tenant, payment))
        val partial = ReverseTransactionCommand(s.tenant, payment, BigDecimal("20"), idempotencyKey = "reverse-1")

        val first = s.facade.limitEvaluationService.reverseTransaction(partial)

        assertEquals(first, s.facade.limitEvaluationService.reverseTransaction(partial))
        assertEquals(TransactionStatus.CAPTURED, first.status)
        assertEquals(BigDecimal("40.0000"), s.usage(rule).consumedAmount)
        val last = s.facade.limitEvaluationService.reverseTransaction(ReverseTransactionCommand(s.tenant, payment))
        assertEquals(TransactionStatus.REVERSED, last.status)
        assertEquals(BigDecimal("40.0000"), last.amount)
        assertEquals(2, history(s, payment).events.count { it.eventType == TransactionEventType.REVERSAL })
        assertEquals(BigDecimal("0.0000"), s.usage(rule).consumedAmount)
    }

    @Test
    fun `expired authorizations refuse mutation and the expiry pass frees only the uncaptured part`() {
        val s = scenario()
        val operation = s.operation("TRANSFER")
        val rule = s.grant(operation, amount = "200")
        val open = payment(s, operation, "40", expiresAt = NOW.plusSeconds(60))
        val partial = payment(s, operation, "100", expiresAt = NOW.plusSeconds(60))
        s.facade.limitEvaluationService.captureTransaction(CaptureTransactionCommand(s.tenant, partial, BigDecimal("25")))
        clock.advanceBy(Duration.ofSeconds(60))

        assertFailsWith<AuthorizationExpiredException> {
            s.facade.limitEvaluationService.captureTransaction(CaptureTransactionCommand(s.tenant, open))
        }
        assertFailsWith<AuthorizationExpiredException> {
            s.facade.limitEvaluationService.releaseAuthorization(ReleaseAuthorizationCommand(s.tenant, open))
        }
        val batch = s.facade.limitEvaluationService.expireDueAuthorizations(ExpireDueAuthorizationsCommand(s.tenant))

        assertEquals(NOW.plusSeconds(60), batch.processedAt)
        val expirations = batch.expirations.associateBy { it.paymentTransactionId }
        assertEquals(TransactionStatus.EXPIRED, expirations.getValue(open).status)
        assertEquals(BigDecimal("40.0000"), expirations.getValue(open).amount)
        assertEquals(TransactionStatus.CAPTURED, expirations.getValue(partial).status)
        assertEquals(BigDecimal("75.0000"), expirations.getValue(partial).amount)
        assertEquals(TransactionEventType.EXPIRY, history(s, open).events.last().eventType)
        assertEquals(BigDecimal("0.0000"), s.usage(rule).reservedAmount)
        assertEquals(BigDecimal("25.0000"), s.usage(rule).consumedAmount)
        assertEquals(emptyList(), s.facade.limitEvaluationService.expireDueAuthorizations(ExpireDueAuthorizationsCommand(s.tenant)).expirations)
    }

    @Test
    fun `an authorization without expiry stays capturable and expiry must lie in the future`() {
        val s = scenario()
        val operation = s.operation("TRANSFER")
        val payment = payment(s, operation, "10")
        for (expiresAt in listOf(NOW, NOW.minusSeconds(1))) {
            assertFailsWith<ValidationException> {
                s.facade.limitEvaluationService.authorizeTransaction(s.command(operation, "10").copy(expiresAt = expiresAt))
            }
        }
        clock.advanceBy(Duration.ofDays(365))

        assertEquals(emptyList(), s.facade.limitEvaluationService.expireDueAuthorizations(ExpireDueAuthorizationsCommand(s.tenant)).expirations)
        assertEquals(TransactionStatus.CAPTURED,
            s.facade.limitEvaluationService.captureTransaction(CaptureTransactionCommand(s.tenant, payment)).status)
    }

    @Test
    fun `authorization replay returns the original decision without reserving twice`() {
        val s = scenario()
        val operation = s.operation("TRANSFER")
        val rule = s.grant(operation, amount = "100")
        val metadata = mapOf("nested" to mapOf("amounts" to listOf(1L, BigDecimal("0.12345678901234567890"))))
        // Sub-microsecond digits are lost in the database and must not break the replay.
        val allowed = s.command(operation, "75", key = "auth-1").copy(metadata = metadata, expiresAt = NOW.plusSeconds(60).plusNanos(789))
        val denied = s.command(operation, "100.01", key = "auth-2")

        val first = s.facade.limitEvaluationService.authorizeTransaction(allowed)
        val rejected = s.facade.limitEvaluationService.authorizeTransaction(denied)
        clock.advanceBy(Duration.ofSeconds(61))

        assertEquals(first, s.facade.limitEvaluationService.authorizeTransaction(allowed))
        assertEquals(rejected, s.facade.limitEvaluationService.authorizeTransaction(denied))
        assertEquals(AuthorizationDecision.DENIED, rejected.decision)
        assertNull(rejected.paymentTransactionId)
        assertEquals(BigDecimal("75.0000"), s.usage(rule).reservedAmount)
        assertFailsWith<IdempotencyConflictException> {
            s.facade.limitEvaluationService.authorizeTransaction(allowed.copy(amount = BigDecimal("76")))
        }
        assertFailsWith<IdempotencyConflictException> {
            s.facade.limitEvaluationService.authorizeTransaction(allowed.copy(
                metadata = mapOf("nested" to mapOf("amounts" to listOf(1L, BigDecimal("0.123")))),
            ))
        }
        assertFailsWith<IdempotencyConflictException> {
            s.facade.limitEvaluationService.authorizeTransaction(denied.copy(amount = BigDecimal("100.02")))
        }
    }

    @Test
    fun `unmatched authorizations follow the tenant behavior`() {
        val facade = PostgresFacade.create(clock, emptyList())
        for ((behavior, expected) in listOf(
            NoMatchingRuleBehavior.DENY to AuthorizationDecision.DENIED,
            NoMatchingRuleBehavior.ALLOW to AuthorizationDecision.ALLOWED,
            NoMatchingRuleBehavior.NOT_APPLICABLE to AuthorizationDecision.NOT_APPLICABLE,
        )) {
            val s = OperationSubtreeScenario(facade, NOW, behavior)
            val command = s.command(s.operation("TRANSFER"), "10", key = "unmatched")

            val result = s.facade.limitEvaluationService.authorizeTransaction(command)

            assertEquals(expected, result.decision, behavior.name)
            assertEquals(result, s.facade.limitEvaluationService.authorizeTransaction(command))
            assertEquals(emptyList(), result.matchedRules)
            assertEquals(behavior == NoMatchingRuleBehavior.ALLOW, result.paymentTransactionId != null, behavior.name)
            if (behavior == NoMatchingRuleBehavior.DENY) {
                assertEquals("NO_MATCHING_LIMIT_RULE", result.violations.single().code)
            }
        }
    }

    @Test
    fun `missing references fail without recording a decision`() {
        val s = scenario()
        val command = s.command(s.operation("TRANSFER"), "10", key = "missing-reference")

        assertFailsWith<NotFoundException> {
            s.facade.limitEvaluationService.authorizeTransaction(command.copy(channelId = UUID.randomUUID()))
        }

        assertEquals(AuthorizationDecision.ALLOWED, s.facade.limitEvaluationService.authorizeTransaction(command).decision)
    }

    @Test
    fun `inactive catalog entries deny new authorizations but existing payments still capture`() {
        for (inactive in listOf("channel", "source", "product")) {
            val s = scenario()
            val operation = s.operation("TRANSFER")
            val payment = payment(s, operation, "25")
            val catalog = s.facade.tenantCatalogService
            val code = when (inactive) {
                "channel" -> catalog.setChannelActive(SetChannelActiveCommand(s.tenant, s.channel, false)).let { "CHANNEL_INACTIVE" }
                "source" -> catalog.setFundingSourceActive(SetFundingSourceActiveCommand(s.tenant, s.source, false))
                    .let { "FUNDING_SOURCE_INACTIVE" }
                else -> catalog.setFundingSourceProductActive(SetFundingSourceProductActiveCommand(s.tenant, s.product, false))
                    .let { "FUNDING_SOURCE_PRODUCT_INACTIVE" }
            }

            val denied = s.authorize(operation, "10")

            assertEquals(AuthorizationDecision.DENIED, denied.decision, inactive)
            assertEquals(code, denied.violations.single().code)
            assertNull(denied.paymentTransactionId)
            assertEquals(TransactionStatus.CAPTURED,
                s.facade.limitEvaluationService.captureTransaction(CaptureTransactionCommand(s.tenant, payment)).status)
        }
    }

    private fun scenario() = OperationSubtreeScenario(PostgresFacade.create(clock, emptyList()), NOW)

    private fun payment(s: OperationSubtreeScenario, operation: UUID, amount: String, expiresAt: Instant? = null): UUID {
        val result = s.facade.limitEvaluationService.authorizeTransaction(s.command(operation, amount).copy(expiresAt = expiresAt))
        assertEquals(AuthorizationDecision.ALLOWED, result.decision)
        return assertNotNull(result.paymentTransactionId)
    }

    private fun history(s: OperationSubtreeScenario, payment: UUID) =
        s.facade.limitInquiryService.getTransactionHistory(GetTransactionHistoryQuery(s.tenant, payment))

    companion object {
        private val NOW = Instant.parse("2026-06-28T10:15:30Z")
    }
}
