package io.github.sepfins.limitguard.application.evaluation

import io.github.sepfins.limitguard.api.AuthorizationDecision
import io.github.sepfins.limitguard.api.LimitUsageEventType as ApiLimitUsageEventType
import io.github.sepfins.limitguard.api.commands.AuthorizeTransactionCommand
import io.github.sepfins.limitguard.api.commands.CaptureTransactionCommand
import io.github.sepfins.limitguard.api.commands.ExpireDueAuthorizationsCommand
import io.github.sepfins.limitguard.api.commands.ReleaseAuthorizationCommand
import io.github.sepfins.limitguard.api.commands.ReverseTransactionCommand
import io.github.sepfins.limitguard.api.errors.IdempotencyConflictException
import io.github.sepfins.limitguard.api.errors.AuthorizationExpiredException
import io.github.sepfins.limitguard.api.errors.NotFoundException
import io.github.sepfins.limitguard.api.errors.ValidationException
import io.github.sepfins.limitguard.api.TransactionStatus as ApiTransactionStatus
import io.github.sepfins.limitguard.application.transaction.TransactionRecorder
import io.github.sepfins.limitguard.application.usage.UsageMutationService
import io.github.sepfins.limitguard.domain.channel.Channel
import io.github.sepfins.limitguard.domain.channel.ChannelCode
import io.github.sepfins.limitguard.domain.channel.ChannelGroupMember
import io.github.sepfins.limitguard.domain.channel.ChannelName
import io.github.sepfins.limitguard.domain.common.ChannelGroupId
import io.github.sepfins.limitguard.domain.common.AuthorizationDecisionId
import io.github.sepfins.limitguard.domain.common.ChannelId
import io.github.sepfins.limitguard.domain.common.CurrencyCode
import io.github.sepfins.limitguard.domain.common.EffectiveWindow
import io.github.sepfins.limitguard.domain.common.FundingSourceGroupId
import io.github.sepfins.limitguard.domain.common.FundingSourceId
import io.github.sepfins.limitguard.domain.common.FundingSourceOwnerId
import io.github.sepfins.limitguard.domain.common.FundingSourceProductId
import io.github.sepfins.limitguard.domain.common.IdempotencyKey
import io.github.sepfins.limitguard.domain.common.LimitRuleId
import io.github.sepfins.limitguard.domain.common.MandateId
import io.github.sepfins.limitguard.domain.common.Metadata
import io.github.sepfins.limitguard.domain.common.Money
import io.github.sepfins.limitguard.domain.common.OperationTypeId
import io.github.sepfins.limitguard.domain.common.PartyId
import io.github.sepfins.limitguard.domain.common.PartyProfileId
import io.github.sepfins.limitguard.domain.common.PaymentTransactionId
import io.github.sepfins.limitguard.domain.common.TenantId
import io.github.sepfins.limitguard.domain.common.TransactionEventId
import io.github.sepfins.limitguard.domain.common.Version
import io.github.sepfins.limitguard.domain.funding.FundingSource
import io.github.sepfins.limitguard.domain.funding.FundingSourceCategory
import io.github.sepfins.limitguard.domain.funding.FundingSourceGroupMember
import io.github.sepfins.limitguard.domain.funding.FundingSourceOwner
import io.github.sepfins.limitguard.domain.funding.FundingSourceOwnerRole
import io.github.sepfins.limitguard.domain.funding.FundingSourceProduct
import io.github.sepfins.limitguard.domain.funding.FundingSourceProductCode
import io.github.sepfins.limitguard.domain.funding.FundingSourceProductName
import io.github.sepfins.limitguard.domain.funding.FundingSourceScope
import io.github.sepfins.limitguard.domain.funding.ProductNodeType
import io.github.sepfins.limitguard.domain.limit.LimitChannelScope
import io.github.sepfins.limitguard.domain.limit.LimitKind
import io.github.sepfins.limitguard.domain.limit.LimitOperationScope
import io.github.sepfins.limitguard.domain.limit.LimitPeriod
import io.github.sepfins.limitguard.domain.limit.LimitPriority
import io.github.sepfins.limitguard.domain.limit.LimitRule
import io.github.sepfins.limitguard.domain.limit.LimitRuleName
import io.github.sepfins.limitguard.domain.limit.LimitUsageConsumptionMode
import io.github.sepfins.limitguard.domain.mandate.Mandate
import io.github.sepfins.limitguard.domain.mandate.MandateName
import io.github.sepfins.limitguard.domain.mandate.MandateScope
import io.github.sepfins.limitguard.domain.operation.OperationType
import io.github.sepfins.limitguard.domain.operation.OperationTypeCode
import io.github.sepfins.limitguard.domain.operation.OperationTypeName
import io.github.sepfins.limitguard.domain.operation.OperationTypeScope
import io.github.sepfins.limitguard.domain.party.PartyProfile
import io.github.sepfins.limitguard.domain.tenant.Tenant
import io.github.sepfins.limitguard.domain.tenant.TenantName
import io.github.sepfins.limitguard.domain.tenant.NoMatchingRuleBehavior
import io.github.sepfins.limitguard.domain.transaction.TransactionEventType
import io.github.sepfins.limitguard.domain.transaction.TransactionStatus as DomainTransactionStatus
import io.github.sepfins.limitguard.application.usage.UsageReservationService
import io.github.sepfins.limitguard.application.usage.UsageWindowCalculator
import io.github.sepfins.limitguard.support.DirectTransactionRunner
import io.github.sepfins.limitguard.support.FixedClockProvider
import io.github.sepfins.limitguard.support.SequentialIdGenerator
import io.github.sepfins.limitguard.support.persistence.InMemoryLimitguardRepositories
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class DefaultLimitEvaluationServiceTest {
    private val now = Instant.parse("2026-06-28T10:15:30Z")
    private val tenantId = TenantId.of(uuid(1))
    private val actorId = PartyProfileId.of(uuid(2))
    private val firstOwnerId = PartyProfileId.of(uuid(3))
    private val secondOwnerId = PartyProfileId.of(uuid(4))
    private val fundingSourceId = FundingSourceId.of(uuid(10))
    private val channelId = ChannelId.of(uuid(11))
    private val operationTypeId = OperationTypeId.of(uuid(12))

    @Test
    fun `under-limit delegated transaction is recorded and allowed`() {
        val fixture = fixture(firstOwnerId)
        val mandate = fixture.addMandate(
            id = 100,
            ownerId = firstOwnerId,
            scope = FundingSourceScope.FundingSourceSpecific(fundingSourceId),
        )
        val rule = fixture.addRule(
            id = 200,
            ownerId = firstOwnerId,
            mandateScope = MandateScope.MandateSpecific(mandate.id),
            amount = "100.00",
        )

        val result = fixture.service.authorizeTransaction(command(amount = "75.00"))

        assertEquals(AuthorizationDecision.ALLOWED, result.decision)
        assertNotNull(result.paymentTransactionId)
        assertNotNull(result.transactionEventId)
        assertEquals(listOf(rule.id.value), result.matchedRules.map { it.id })
        assertEquals(1, fixture.repositories.transactions.findByFundingSourceId(tenantId, fundingSourceId).size)
        assertEquals(
            TransactionEventType.AUTHORIZATION,
            fixture.repositories.transactionEvents
                .findByTransaction(
                    tenantId,
                    PaymentTransactionId.of(requireNotNull(result.paymentTransactionId)),
                )
                .single()
                .eventType,
        )
    }

    @Test
    fun `over-limit transaction persists its decision but no payment transaction`() {
        val fixture = fixture(firstOwnerId)
        val mandate = fixture.addMandate(
            id = 100,
            ownerId = firstOwnerId,
            scope = FundingSourceScope.AllOwnerSources,
        )
        val rule = fixture.addRule(
            id = 200,
            ownerId = firstOwnerId,
            mandateScope = MandateScope.MandateSpecific(mandate.id),
            amount = "100.00",
        )

        val result = fixture.service.authorizeTransaction(command(amount = "100.01"))

        assertEquals(AuthorizationDecision.DENIED, result.decision)
        assertNull(result.paymentTransactionId)
        assertNull(result.transactionEventId)
        assertEquals("PER_TRANSACTION_LIMIT_EXCEEDED", result.violations.single().code)
        assertEquals(rule.id.value, result.violations.single().limitRuleId)
        assertEquals(emptyList(), fixture.repositories.transactions.findByFundingSourceId(tenantId, fundingSourceId))
        val persistedDecision = assertNotNull(
            fixture.repositories.authorizationDecisions.findById(
                tenantId,
                AuthorizationDecisionId.of(result.authorizationDecisionId),
            ),
        )
        assertEquals(result.decision.name, persistedDecision.decision.name)
        assertNull(persistedDecision.paymentTransactionId)
    }

    @Test
    fun `successful authorization replay returns the original result and rejects changed intent`() {
        val fixture = fixture(firstOwnerId)
        fixture.addMandate(
            id = 100,
            ownerId = firstOwnerId,
            scope = FundingSourceScope.AllOwnerSources,
        )
        fixture.addRule(
            id = 200,
            ownerId = firstOwnerId,
            mandateScope = MandateScope.AllOwnerMandates,
            amount = "100.00",
        )

        val first = fixture.service.authorizeTransaction(command(amount = "75.00", idempotencyKey = "auth-1"))
        val persistedTransaction = assertNotNull(
            fixture.repositories.transactions.findById(
                tenantId,
                PaymentTransactionId.of(assertNotNull(first.paymentTransactionId)),
            ),
        )
        fixture.repositories.transactions.update(
            tenantId,
            persistedTransaction.copy(
                metadata = Metadata(mapOf("changedAfterAuthorization" to true)),
            ),
        )
        val replay = fixture.service.authorizeTransaction(command(amount = "75.00", idempotencyKey = "auth-1"))

        assertEquals(first, replay)
        assertEquals(1, fixture.repositories.transactions.findByFundingSourceId(tenantId, fundingSourceId).size)
        val authorizationDecision = assertNotNull(
            fixture.repositories.authorizationDecisions
                .findByIdempotencyKey(tenantId, IdempotencyKey.of("auth-1")),
        )
        val authorizationEvent = assertNotNull(
            fixture.repositories.transactionEvents.findById(
                tenantId,
                TransactionEventId.of(assertNotNull(first.transactionEventId)),
            ),
        )
        assertEquals(TransactionEventType.AUTHORIZATION, authorizationEvent.eventType)
        assertNull(authorizationEvent.idempotencyKey)
        assertEquals(
            first.paymentTransactionId.toString(),
            authorizationDecision.resultSnapshot.value["paymentTransactionId"],
        )
        assertEquals(
            first.transactionEventId.toString(),
            authorizationDecision.resultSnapshot.value["transactionEventId"],
        )
        assertNull(authorizationEvent.metadata.value["matchedRules"])
        assertNotNull(authorizationEvent.metadata.value["fundingAuthorities"])
        assertFailsWith<IdempotencyConflictException> {
            fixture.service.authorizeTransaction(command(amount = "76.00", idempotencyKey = "auth-1"))
        }
        assertFailsWith<IdempotencyConflictException> {
            fixture.service.authorizeTransaction(
                command(amount = "75.00", idempotencyKey = "auth-1")
                    .copy(metadata = mapOf("merchantCategory" to "TRAVEL")),
            )
        }
    }

    @Test
    fun `authorization and mutation requests share one idempotency key namespace`() {
        val fixture = fixture(actorId, noMatchingRuleBehavior = NoMatchingRuleBehavior.ALLOW)
        val idempotentAuthorization = fixture.service.authorizeTransaction(
            command(amount = "25.00", idempotencyKey = "authorization-key"),
        )

        assertFailsWith<IdempotencyConflictException> {
            fixture.service.captureTransaction(
                captureCommand(
                    paymentTransactionId = assertNotNull(idempotentAuthorization.paymentTransactionId),
                    amount = "5.00",
                    idempotencyKey = "authorization-key",
                ),
            )
        }

        val otherAuthorization = fixture.service.authorizeTransaction(command(amount = "25.00"))
        fixture.service.captureTransaction(
            captureCommand(
                paymentTransactionId = assertNotNull(otherAuthorization.paymentTransactionId),
                amount = "5.00",
                idempotencyKey = "mutation-key",
            ),
        )

        assertFailsWith<IdempotencyConflictException> {
            fixture.service.authorizeTransaction(
                command(amount = "25.00", idempotencyKey = "mutation-key"),
            )
        }
    }

    @Test
    fun `denied authorization replays the original decision and rejects changed intent`() {
        val fixture = fixture(firstOwnerId)
        fixture.addMandate(
            id = 100,
            ownerId = firstOwnerId,
            scope = FundingSourceScope.AllOwnerSources,
        )
        val rule = fixture.addRule(
            id = 200,
            ownerId = firstOwnerId,
            mandateScope = MandateScope.AllOwnerMandates,
            amount = "100.00",
        )
        val command = command(amount = "100.01", idempotencyKey = "denied-auth-1")

        val first = fixture.service.authorizeTransaction(command)
        fixture.repositories.limitRules.update(
            tenantId,
            rule.copy(amount = Money.positive("1000.00", CurrencyCode.of("EUR"))),
        )
        val replay = fixture.service.authorizeTransaction(command)

        assertEquals(AuthorizationDecision.DENIED, first.decision)
        assertEquals(first, replay)
        assertNull(replay.paymentTransactionId)
        assertEquals(
            first.authorizationDecisionId,
            fixture.repositories.authorizationDecisions
                .findByIdempotencyKey(tenantId, IdempotencyKey.of("denied-auth-1"))
                ?.id
                ?.value,
        )
        assertFailsWith<IdempotencyConflictException> {
            fixture.service.authorizeTransaction(command(amount = "100.02", idempotencyKey = "denied-auth-1"))
        }
    }

    @Test
    fun `authorization replay remains stable after its expiry time`() {
        val fixture = fixture(actorId, noMatchingRuleBehavior = NoMatchingRuleBehavior.ALLOW)
        val command = command(
            amount = "25.00",
            idempotencyKey = "expiring-auth-1",
            expiresAt = now.plusSeconds(60),
        )
        val first = fixture.service.authorizeTransaction(command)
        fixture.clock.advanceBy(Duration.ofSeconds(61))

        val replay = fixture.service.authorizeTransaction(command)

        assertEquals(first, replay)
        assertEquals(AuthorizationDecision.ALLOWED, replay.decision)
    }

    @Test
    fun `capture records transaction and moves periodic usage from reserved to consumed`() {
        val fixture = fixture(firstOwnerId)
        fixture.addMandate(
            id = 100,
            ownerId = firstOwnerId,
            scope = FundingSourceScope.AllOwnerSources,
        )
        val dailyRule = fixture.addRule(
            id = 200,
            ownerId = firstOwnerId,
            mandateScope = MandateScope.AllOwnerMandates,
            amount = "100.00",
            limitKind = LimitKind.Periodic(LimitPeriod.DAILY),
        )
        val authorization = fixture.service.authorizeTransaction(command(amount = "25.00"))
        val transactionId = requireNotNull(authorization.paymentTransactionId)

        val result = fixture.service.captureTransaction(
            captureCommand(
                paymentTransactionId = transactionId,
                reason = " settlement ",
                metadata = mapOf("processorReference" to "capture-001"),
            ),
        )

        assertEquals(ApiTransactionStatus.CAPTURED, result.status)
        assertEquals(BigDecimal("25.0000"), result.amount)
        assertEquals("EUR", result.currency)
        assertEquals(1, result.usageEvents.size)
        assertEquals(ApiLimitUsageEventType.CONSUME, result.usageEvents.single().eventType)
        assertEquals(BigDecimal("25.0000"), result.usageEvents.single().amountDelta)
        assertEquals(mapOf("processorReference" to "capture-001"), result.metadata)

        val persistedTransaction = assertNotNull(
            fixture.repositories.transactions.findById(
                tenantId,
                PaymentTransactionId.of(transactionId),
            ),
        )
        assertEquals(DomainTransactionStatus.CAPTURED, persistedTransaction.status)
        assertEquals(
            Money.positive("25.00", CurrencyCode.of("EUR")),
            persistedTransaction.capturedAmount,
        )

        val captureEvent = fixture.repositories.transactionEvents
            .findByTransaction(tenantId, persistedTransaction.id)
            .single { it.eventType == TransactionEventType.CAPTURE }
        assertEquals(result.transactionEventId, captureEvent.id.value)
        assertEquals(Money.positive("25.00", CurrencyCode.of("EUR")), captureEvent.amount)
        assertNull(captureEvent.requestedAmount)
        assertEquals("settlement", captureEvent.reason?.value)
        assertEquals(mapOf("processorReference" to "capture-001"), captureEvent.metadata.value)
        assertNull(captureEvent.idempotencyKey)
        assertNull(captureEvent.resultSnapshot)
        val usageEvent = fixture.repositories.usageEvents
            .findByTransactionEvent(tenantId, captureEvent.id)
            .single()
        assertEquals(dailyRule.id, usageEvent.limitRuleId)
        assertEquals(BigDecimal("25.0000"), usageEvent.amountDelta)

        val bucket = fixture.periodicBucket(dailyRule, LimitPeriod.DAILY)
        assertEquals(Money.zero(CurrencyCode.of("EUR")), bucket.reservedAmount)
        assertEquals(Money.positive("25.00", CurrencyCode.of("EUR")), bucket.consumedAmount)
    }

    @Test
    fun `release voids uncaptured authorization and releases periodic usage`() {
        val fixture = fixture(firstOwnerId)
        fixture.addMandate(
            id = 100,
            ownerId = firstOwnerId,
            scope = FundingSourceScope.AllOwnerSources,
        )
        val dailyRule = fixture.addRule(
            id = 200,
            ownerId = firstOwnerId,
            mandateScope = MandateScope.AllOwnerMandates,
            amount = "100.00",
            limitKind = LimitKind.Periodic(LimitPeriod.DAILY),
        )
        val authorization = fixture.service.authorizeTransaction(command(amount = "40.00"))
        val transactionId = requireNotNull(authorization.paymentTransactionId)

        val first = fixture.service.releaseAuthorization(
            releaseCommand(
                paymentTransactionId = transactionId,
                idempotencyKey = "release-1",
                metadata = mapOf("processorReference" to "release-001"),
            ),
        )
        val replay = fixture.service.releaseAuthorization(
            releaseCommand(
                paymentTransactionId = transactionId,
                idempotencyKey = "release-1",
                metadata = mapOf("processorReference" to "release-001"),
            ),
        )

        assertEquals(first, replay)
        assertEquals(ApiTransactionStatus.VOIDED, first.status)
        assertEquals(BigDecimal("40.0000"), first.amount)
        assertEquals(ApiLimitUsageEventType.RELEASE, first.usageEvents.single().eventType)
        assertEquals(BigDecimal("-40.0000"), first.usageEvents.single().amountDelta)

        val transaction = assertNotNull(
            fixture.repositories.transactions.findById(tenantId, PaymentTransactionId.of(transactionId)),
        )
        assertEquals(DomainTransactionStatus.VOIDED, transaction.status)
        assertEquals(Money.zero(CurrencyCode.of("EUR")), transaction.authorizedAmount)
        assertEquals(Money.zero(CurrencyCode.of("EUR")), transaction.capturedAmount)

        val releaseEvent = fixture.repositories.transactionEvents
            .findByTransaction(tenantId, transaction.id)
            .single { it.eventType == TransactionEventType.VOID }
        assertEquals(first.transactionEventId, releaseEvent.id.value)
        assertEquals(1, fixture.repositories.usageEvents.findByTransactionEvent(tenantId, releaseEvent.id).size)

        val bucket = fixture.periodicBucket(dailyRule, LimitPeriod.DAILY)
        assertEquals(Money.zero(CurrencyCode.of("EUR")), bucket.reservedAmount)
        assertEquals(Money.zero(CurrencyCode.of("EUR")), bucket.consumedAmount)
    }

    @Test
    fun `release after partial capture only releases the remaining reserved usage`() {
        val fixture = fixture(firstOwnerId)
        fixture.addMandate(
            id = 100,
            ownerId = firstOwnerId,
            scope = FundingSourceScope.AllOwnerSources,
        )
        val dailyRule = fixture.addRule(
            id = 200,
            ownerId = firstOwnerId,
            mandateScope = MandateScope.AllOwnerMandates,
            amount = "100.00",
            limitKind = LimitKind.Periodic(LimitPeriod.DAILY),
        )
        val authorization = fixture.service.authorizeTransaction(command(amount = "60.00"))
        val transactionId = requireNotNull(authorization.paymentTransactionId)
        fixture.service.captureTransaction(captureCommand(transactionId, amount = "20.00"))

        val result = fixture.service.releaseAuthorization(releaseCommand(transactionId))

        assertEquals(ApiTransactionStatus.CAPTURED, result.status)
        assertEquals(BigDecimal("40.0000"), result.amount)
        assertEquals(ApiLimitUsageEventType.RELEASE, result.usageEvents.single().eventType)
        assertEquals(BigDecimal("-40.0000"), result.usageEvents.single().amountDelta)

        val transaction = assertNotNull(
            fixture.repositories.transactions.findById(tenantId, PaymentTransactionId.of(transactionId)),
        )
        assertEquals(DomainTransactionStatus.CAPTURED, transaction.status)
        assertEquals(Money.positive("20.00", CurrencyCode.of("EUR")), transaction.authorizedAmount)
        assertEquals(Money.positive("20.00", CurrencyCode.of("EUR")), transaction.capturedAmount)
        val releaseEvent = fixture.repositories.transactionEvents
            .findByTransaction(tenantId, transaction.id)
            .single { it.eventType == TransactionEventType.ADJUSTMENT }
        assertEquals(result.transactionEventId, releaseEvent.id.value)

        val bucket = fixture.periodicBucket(dailyRule, LimitPeriod.DAILY)
        assertEquals(Money.zero(CurrencyCode.of("EUR")), bucket.reservedAmount)
        assertEquals(Money.positive("20.00", CurrencyCode.of("EUR")), bucket.consumedAmount)
    }

    @Test
    fun `authorization expiry must be in the future`() {
        val fixture = fixture(actorId, noMatchingRuleBehavior = NoMatchingRuleBehavior.ALLOW)

        assertFailsWith<ValidationException> {
            fixture.service.authorizeTransaction(command(amount = "10.00", expiresAt = now))
        }
        assertFailsWith<ValidationException> {
            fixture.service.authorizeTransaction(command(amount = "10.00", expiresAt = now.minusSeconds(1)))
        }

        assertEquals(emptyList(), fixture.repositories.transactions.findByFundingSourceId(tenantId, fundingSourceId))
    }

    @Test
    fun `missing references remain errors and do not create decision records`() {
        val fixture = fixture(actorId, noMatchingRuleBehavior = NoMatchingRuleBehavior.ALLOW)
        val command = command(amount = "10.00", idempotencyKey = "missing-reference-1")
            .copy(channelId = uuid(999))

        assertFailsWith<NotFoundException> {
            fixture.service.authorizeTransaction(command)
        }

        assertNull(
            fixture.repositories.authorizationDecisions.findByIdempotencyKey(
                tenantId,
                IdempotencyKey.of("missing-reference-1"),
            ),
        )
    }

    @Test
    fun `expired authorization rejects capture and release then frees reserved usage`() {
        val fixture = fixture(firstOwnerId)
        fixture.addMandate(
            id = 100,
            ownerId = firstOwnerId,
            scope = FundingSourceScope.AllOwnerSources,
        )
        val rule = fixture.addRule(
            id = 200,
            ownerId = firstOwnerId,
            mandateScope = MandateScope.AllOwnerMandates,
            amount = "100.00",
            limitKind = LimitKind.Periodic(LimitPeriod.DAILY),
        )
        val authorization = fixture.service.authorizeTransaction(
            command(amount = "40.00", expiresAt = now.plusSeconds(60)),
        )
        val transactionId = assertNotNull(authorization.paymentTransactionId)

        fixture.clock.advanceBy(Duration.ofSeconds(60))

        assertFailsWith<AuthorizationExpiredException> {
            fixture.service.captureTransaction(captureCommand(transactionId))
        }
        assertFailsWith<AuthorizationExpiredException> {
            fixture.service.releaseAuthorization(releaseCommand(transactionId))
        }

        val batch = fixture.service.expireDueAuthorizations(
            ExpireDueAuthorizationsCommand(tenantId = tenantId.value),
        )
        val expiration = batch.expirations.single()
        val persisted = assertNotNull(
            fixture.repositories.transactions.findById(tenantId, PaymentTransactionId.of(transactionId)),
        )

        assertEquals(now.plusSeconds(60), batch.processedAt)
        assertEquals(ApiTransactionStatus.EXPIRED, expiration.status)
        assertEquals(BigDecimal("40.0000"), expiration.amount)
        assertEquals(ApiLimitUsageEventType.RELEASE, expiration.usageEvents.single().eventType)
        assertEquals(BigDecimal("-40.0000"), expiration.usageEvents.single().amountDelta)
        assertEquals(DomainTransactionStatus.EXPIRED, persisted.status)
        assertEquals(Money.zero(CurrencyCode.of("EUR")), fixture.periodicBucket(rule, LimitPeriod.DAILY).reservedAmount)
        assertEquals(
            TransactionEventType.EXPIRY,
            fixture.repositories.transactionEvents
                .findByTransaction(tenantId, PaymentTransactionId.of(transactionId))
                .last()
                .eventType,
        )
        assertEquals(
            emptyList(),
            fixture.service.expireDueAuthorizations(
                ExpireDueAuthorizationsCommand(tenantId = tenantId.value),
            ).expirations,
        )
    }

    @Test
    fun `expiry closes only the uncaptured portion of a partially captured authorization`() {
        val fixture = fixture(firstOwnerId)
        fixture.addMandate(
            id = 100,
            ownerId = firstOwnerId,
            scope = FundingSourceScope.AllOwnerSources,
        )
        val rule = fixture.addRule(
            id = 200,
            ownerId = firstOwnerId,
            mandateScope = MandateScope.AllOwnerMandates,
            amount = "100.00",
            limitKind = LimitKind.Periodic(LimitPeriod.DAILY),
        )
        val authorization = fixture.service.authorizeTransaction(
            command(amount = "100.00", expiresAt = now.plusSeconds(60)),
        )
        val transactionId = assertNotNull(authorization.paymentTransactionId)
        fixture.service.captureTransaction(captureCommand(transactionId, amount = "25.00"))
        fixture.clock.advanceBy(Duration.ofSeconds(60))

        val expiration = fixture.service.expireDueAuthorizations(
            ExpireDueAuthorizationsCommand(tenantId = tenantId.value),
        ).expirations.single()
        val persisted = assertNotNull(
            fixture.repositories.transactions.findById(tenantId, PaymentTransactionId.of(transactionId)),
        )
        val bucket = fixture.periodicBucket(rule, LimitPeriod.DAILY)

        assertEquals(ApiTransactionStatus.CAPTURED, expiration.status)
        assertEquals(BigDecimal("75.0000"), expiration.amount)
        assertEquals(DomainTransactionStatus.CAPTURED, persisted.status)
        assertEquals(Money.positive("25.00", CurrencyCode.of("EUR")), persisted.authorizedAmount)
        assertEquals(Money.positive("25.00", CurrencyCode.of("EUR")), persisted.capturedAmount)
        assertEquals(Money.zero(CurrencyCode.of("EUR")), bucket.reservedAmount)
        assertEquals(Money.positive("25.00", CurrencyCode.of("EUR")), bucket.consumedAmount)
    }

    @Test
    fun `authorization without an expiry remains capturable`() {
        val fixture = fixture(actorId, noMatchingRuleBehavior = NoMatchingRuleBehavior.ALLOW)
        val authorization = fixture.service.authorizeTransaction(command(amount = "10.00"))
        val transactionId = assertNotNull(authorization.paymentTransactionId)
        fixture.clock.advanceBy(Duration.ofDays(365))

        val expiryBatch = fixture.service.expireDueAuthorizations(
            ExpireDueAuthorizationsCommand(tenantId = tenantId.value),
        )
        val capture = fixture.service.captureTransaction(captureCommand(transactionId))

        assertEquals(emptyList(), expiryBatch.expirations)
        assertEquals(ApiTransactionStatus.CAPTURED, capture.status)
    }

    @Test
    fun `reverse captured transaction reduces consumed periodic usage`() {
        val fixture = fixture(firstOwnerId)
        fixture.addMandate(
            id = 100,
            ownerId = firstOwnerId,
            scope = FundingSourceScope.AllOwnerSources,
        )
        val dailyRule = fixture.addRule(
            id = 200,
            ownerId = firstOwnerId,
            mandateScope = MandateScope.AllOwnerMandates,
            amount = "100.00",
            limitKind = LimitKind.Periodic(LimitPeriod.DAILY),
        )
        val authorization = fixture.service.authorizeTransaction(command(amount = "60.00"))
        val transactionId = requireNotNull(authorization.paymentTransactionId)
        fixture.service.captureTransaction(captureCommand(transactionId))

        val firstReverse = fixture.service.reverseTransaction(
            reverseCommand(
                paymentTransactionId = transactionId,
                amount = "20.00",
                idempotencyKey = "reverse-1",
            ),
        )
        val replay = fixture.service.reverseTransaction(
            reverseCommand(
                paymentTransactionId = transactionId,
                amount = "20.00",
                idempotencyKey = "reverse-1",
            ),
        )

        assertEquals(firstReverse, replay)
        assertEquals(ApiTransactionStatus.CAPTURED, firstReverse.status)
        assertEquals(BigDecimal("20.0000"), firstReverse.amount)
        assertEquals(ApiLimitUsageEventType.REVERSAL, firstReverse.usageEvents.single().eventType)
        assertEquals(BigDecimal("-20.0000"), firstReverse.usageEvents.single().amountDelta)

        val finalReverse = fixture.service.reverseTransaction(reverseCommand(transactionId))
        assertEquals(ApiTransactionStatus.REVERSED, finalReverse.status)
        assertEquals(BigDecimal("40.0000"), finalReverse.amount)
        assertEquals(BigDecimal("-40.0000"), finalReverse.usageEvents.single().amountDelta)

        val transaction = assertNotNull(
            fixture.repositories.transactions.findById(tenantId, PaymentTransactionId.of(transactionId)),
        )
        assertEquals(DomainTransactionStatus.REVERSED, transaction.status)
        assertEquals(Money.zero(CurrencyCode.of("EUR")), transaction.authorizedAmount)
        assertEquals(Money.zero(CurrencyCode.of("EUR")), transaction.capturedAmount)

        val reversalEvents = fixture.repositories.transactionEvents
            .findByTransaction(tenantId, transaction.id)
            .filter { it.eventType == TransactionEventType.REVERSAL }
        assertEquals(2, reversalEvents.size)

        val bucket = fixture.periodicBucket(dailyRule, LimitPeriod.DAILY)
        assertEquals(Money.zero(CurrencyCode.of("EUR")), bucket.reservedAmount)
        assertEquals(Money.zero(CurrencyCode.of("EUR")), bucket.consumedAmount)
    }

    @Test
    fun `idempotent partial capture replays the completed result snapshot`() {
        val fixture = fixture(firstOwnerId)
        fixture.addMandate(
            id = 100,
            ownerId = firstOwnerId,
            scope = FundingSourceScope.AllOwnerSources,
        )
        fixture.addRule(
            id = 200,
            ownerId = firstOwnerId,
            mandateScope = MandateScope.AllOwnerMandates,
            amount = "100.00",
        )
        val authorization = fixture.service.authorizeTransaction(command(amount = "75.00"))
        val transactionId = requireNotNull(authorization.paymentTransactionId)
        val capture = captureCommand(
            paymentTransactionId = transactionId,
            amount = "25.00",
            reason = "first presentment",
            idempotencyKey = "capture-1",
            metadata = mapOf("processorReference" to "capture-002"),
        )

        val first = fixture.service.captureTransaction(capture)
        val replay = fixture.service.captureTransaction(capture)

        assertEquals(first, replay)
        assertEquals(ApiTransactionStatus.PARTIALLY_CAPTURED, first.status)
        assertEquals(BigDecimal("25.0000"), first.amount)
        assertEquals(mapOf("processorReference" to "capture-002"), replay.metadata)

        val transaction = assertNotNull(
            fixture.repositories.transactions.findById(
                tenantId,
                PaymentTransactionId.of(transactionId),
            ),
        )
        assertEquals(Money.positive("25.00", CurrencyCode.of("EUR")), transaction.capturedAmount)
        val events = fixture.repositories.transactionEvents.findByTransaction(tenantId, transaction.id)
        assertEquals(2, events.size)
        val captureEvent = events.single { it.eventType == TransactionEventType.PARTIAL_CAPTURE }
        assertEquals(IdempotencyKey.of("capture-1"), captureEvent.idempotencyKey)
        assertEquals(Money.positive("25.00", CurrencyCode.of("EUR")), captureEvent.requestedAmount)
        assertEquals(
            first.transactionEventId.toString(),
            captureEvent.resultSnapshot?.value?.get("transactionEventId"),
        )

        assertFailsWith<IdempotencyConflictException> {
            fixture.service.captureTransaction(capture.copy(amount = BigDecimal("26.00")))
        }
        assertFailsWith<IdempotencyConflictException> {
            fixture.service.captureTransaction(capture.copy(amount = null))
        }
        assertFailsWith<IdempotencyConflictException> {
            fixture.service.captureTransaction(capture.copy(reason = "second presentment"))
        }
        assertFailsWith<IdempotencyConflictException> {
            fixture.service.captureTransaction(
                capture.copy(metadata = mapOf("processorReference" to "capture-003")),
            )
        }
    }

    @Test
    fun `all group and source mandates may overlap and every attached rule is enforced`() {
        val fixture = fixture(firstOwnerId)
        val groupId = FundingSourceGroupId.of(uuid(50))
        fixture.repositories.fundingSourceGroupMembers.add(
            tenantId,
            FundingSourceGroupMember(
                tenantId = tenantId,
                groupId = groupId,
                ownerPartyProfileId = firstOwnerId,
                fundingSourceId = fundingSourceId,
                createdAt = now,
            ),
        )
        val allSourcesMandate = fixture.addMandate(
            id = 100,
            ownerId = firstOwnerId,
            scope = FundingSourceScope.AllOwnerSources,
        )
        val groupMandate = fixture.addMandate(
            id = 101,
            ownerId = firstOwnerId,
            scope = FundingSourceScope.FundingSourceGroupSpecific(groupId),
        )
        val sourceMandate = fixture.addMandate(
            id = 102,
            ownerId = firstOwnerId,
            scope = FundingSourceScope.FundingSourceSpecific(fundingSourceId),
        )
        val rules = listOf(
            fixture.addRule(200, firstOwnerId, MandateScope.AllOwnerMandates, "1000.00", priority = 40),
            fixture.addRule(201, firstOwnerId, MandateScope.MandateSpecific(allSourcesMandate.id), "500.00", priority = 30),
            fixture.addRule(202, firstOwnerId, MandateScope.MandateSpecific(groupMandate.id), "300.00", priority = 20),
            fixture.addRule(203, firstOwnerId, MandateScope.MandateSpecific(sourceMandate.id), "100.00", priority = 10),
        )

        val result = fixture.service.authorizeTransaction(command(amount = "150.00"))

        assertEquals(AuthorizationDecision.DENIED, result.decision)
        assertEquals(
            rules.sortedBy { it.priority.value }.map { it.id.value },
            result.matchedRules.map { it.id },
        )
        assertEquals(listOf(uuid(203)), result.violations.map { it.limitRuleId })
    }

    @Test
    fun `co-owners may grant separate mandates and rules from both authority paths apply`() {
        val fixture = fixture(firstOwnerId, secondOwnerId)
        val firstMandate = fixture.addMandate(
            id = 100,
            ownerId = firstOwnerId,
            scope = FundingSourceScope.FundingSourceSpecific(fundingSourceId),
        )
        val secondMandate = fixture.addMandate(
            id = 101,
            ownerId = secondOwnerId,
            scope = FundingSourceScope.AllOwnerSources,
        )
        val restrictiveRule = fixture.addRule(
            id = 200,
            ownerId = firstOwnerId,
            mandateScope = MandateScope.MandateSpecific(firstMandate.id),
            amount = "100.00",
        )
        val otherOwnerRule = fixture.addRule(
            id = 201,
            ownerId = secondOwnerId,
            mandateScope = MandateScope.MandateSpecific(secondMandate.id),
            amount = "200.00",
        )

        val result = fixture.service.authorizeTransaction(command(amount = "150.00"))

        assertEquals(AuthorizationDecision.DENIED, result.decision)
        assertEquals(
            setOf(restrictiveRule.id.value, otherOwnerRule.id.value),
            result.matchedRules.mapTo(mutableSetOf()) { it.id },
        )
        assertEquals(restrictiveRule.id.value, result.violations.single().limitRuleId)
    }

    @Test
    fun `group mandate does not authorize a source outside the group`() {
        val fixture = fixture(firstOwnerId)
        fixture.addMandate(
            id = 100,
            ownerId = firstOwnerId,
            scope = FundingSourceScope.FundingSourceGroupSpecific(FundingSourceGroupId.of(uuid(50))),
        )

        val result = fixture.service.authorizeTransaction(command(amount = "10.00"))

        assertEquals(AuthorizationDecision.DENIED, result.decision)
        assertEquals("ACTOR_NOT_AUTHORIZED", result.violations.single().code)
        assertEquals(emptyList(), fixture.repositories.transactions.findByFundingSourceId(tenantId, fundingSourceId))
    }

    @Test
    fun `inactive mandate does not authorize an otherwise matching source`() {
        val fixture = fixture(firstOwnerId)
        fixture.addMandate(
            id = 100,
            ownerId = firstOwnerId,
            scope = FundingSourceScope.AllOwnerSources,
            isActive = false,
        )

        val result = fixture.service.authorizeTransaction(command(amount = "10.00"))

        assertEquals(AuthorizationDecision.DENIED, result.decision)
        assertEquals("ACTOR_NOT_AUTHORIZED", result.violations.single().code)
    }

    @Test
    fun `inactive channel denies and audits a new authorization`() {
        val fixture = fixture(actorId)
        val channel = assertNotNull(fixture.repositories.channels.findById(tenantId, channelId))
        fixture.repositories.channels.update(tenantId, channel.copy(isActive = false))

        val result = fixture.service.authorizeTransaction(command(amount = "10.00"))

        assertEquals(AuthorizationDecision.DENIED, result.decision)
        assertEquals("CHANNEL_INACTIVE", result.violations.single().code)
        assertNull(result.paymentTransactionId)
        assertNotNull(
            fixture.repositories.authorizationDecisions.findById(
                tenantId,
                AuthorizationDecisionId.of(result.authorizationDecisionId),
            ),
        )
    }

    @Test
    fun `inactive funding source denies a new authorization`() {
        val fixture = fixture(actorId)
        val source = assertNotNull(fixture.repositories.fundingSources.findById(tenantId, fundingSourceId))
        fixture.repositories.fundingSources.update(tenantId, source.copy(isActive = false))

        val result = fixture.service.authorizeTransaction(command(amount = "10.00"))

        assertEquals(AuthorizationDecision.DENIED, result.decision)
        assertEquals("FUNDING_SOURCE_INACTIVE", result.violations.single().code)
        assertNull(result.paymentTransactionId)
    }

    @Test
    fun `catalog deactivation does not invalidate an existing authorization`() {
        val fixture = fixture(firstOwnerId)
        fixture.addMandate(
            id = 100,
            ownerId = firstOwnerId,
            scope = FundingSourceScope.AllOwnerSources,
        )
        fixture.addRule(
            id = 200,
            ownerId = firstOwnerId,
            mandateScope = MandateScope.AllOwnerMandates,
            amount = "100.00",
        )
        val authorization = fixture.service.authorizeTransaction(command(amount = "25.00"))
        val source = assertNotNull(fixture.repositories.fundingSources.findById(tenantId, fundingSourceId))
        fixture.repositories.fundingSources.update(tenantId, source.copy(isActive = false))

        val capture = fixture.service.captureTransaction(
            captureCommand(assertNotNull(authorization.paymentTransactionId)),
        )

        assertEquals(ApiTransactionStatus.CAPTURED, capture.status)
    }

    @Test
    fun `inactive ancestor product disables its descendant product`() {
        val fixture = fixture(actorId)
        val parentId = FundingSourceProductId.of(uuid(21))
        fixture.repositories.fundingSourceProducts.insert(
            tenantId,
            FundingSourceProduct(
                id = parentId,
                tenantId = tenantId,
                parentId = null,
                accountCategory = FundingSourceCategory.TRANSACTIONAL,
                nodeType = ProductNodeType.FAMILY,
                code = FundingSourceProductCode.of("ACCOUNTS"),
                name = FundingSourceProductName.of("Accounts"),
                isLeaf = false,
                isActive = false,
                createdAt = now,
            ),
        )
        val leafId = FundingSourceProductId.of(uuid(20))
        val leaf = assertNotNull(fixture.repositories.fundingSourceProducts.findById(tenantId, leafId))
        fixture.repositories.fundingSourceProducts.update(tenantId, leaf.copy(parentId = parentId))

        val result = fixture.service.authorizeTransaction(command(amount = "10.00"))

        assertEquals(AuthorizationDecision.DENIED, result.decision)
        assertEquals("FUNDING_SOURCE_PRODUCT_INACTIVE", result.violations.single().code)
        assertNull(result.paymentTransactionId)
    }

    @Test
    fun `channel-group rule matches through channel membership`() {
        val fixture = fixture(firstOwnerId)
        fixture.addMandate(
            id = 100,
            ownerId = firstOwnerId,
            scope = FundingSourceScope.AllOwnerSources,
        )
        val channelGroupId = ChannelGroupId.of(uuid(60))
        fixture.repositories.channelGroupMembers.add(
            tenantId,
            ChannelGroupMember(
                tenantId = tenantId,
                groupId = channelGroupId,
                channelId = channelId,
                createdAt = now,
            ),
        )
        val rule = fixture.addRule(
            id = 200,
            ownerId = firstOwnerId,
            mandateScope = MandateScope.AllOwnerMandates,
            amount = "25.00",
            channelScope = LimitChannelScope.ChannelGroupSpecific(channelGroupId),
        )

        val result = fixture.service.authorizeTransaction(command(amount = "30.00"))

        assertEquals(AuthorizationDecision.DENIED, result.decision)
        assertEquals(rule.id.value, result.violations.single().limitRuleId)
    }

    @Test
    fun `tenant fails closed when a direct owner has no matching rule`() {
        val fixture = fixture(actorId)

        val result = fixture.service.authorizeTransaction(command(amount = "10.00"))

        assertEquals(AuthorizationDecision.DENIED, result.decision)
        assertEquals(emptyList(), result.matchedRules)
        assertEquals("NO_MATCHING_LIMIT_RULE", result.violations.single().code)
        assertNull(result.paymentTransactionId)
    }

    @Test
    fun `tenant may explicitly allow an authorization with no matching rule`() {
        val fixture = fixture(
            ownerId = actorId,
            noMatchingRuleBehavior = NoMatchingRuleBehavior.ALLOW,
        )

        val result = fixture.service.authorizeTransaction(command(amount = "10.00"))

        assertEquals(AuthorizationDecision.ALLOWED, result.decision)
        assertEquals(emptyList(), result.matchedRules)
        assertNotNull(result.paymentTransactionId)
        assertNotNull(result.transactionEventId)
    }

    @Test
    fun `tenant may delegate a no-rule decision to the consuming application`() {
        val fixture = fixture(
            ownerId = actorId,
            noMatchingRuleBehavior = NoMatchingRuleBehavior.NOT_APPLICABLE,
        )

        val command = command(amount = "10.00", idempotencyKey = "not-applicable-1")
        val result = fixture.service.authorizeTransaction(command)
        val replay = fixture.service.authorizeTransaction(command)

        assertEquals(AuthorizationDecision.NOT_APPLICABLE, result.decision)
        assertEquals(result, replay)
        assertEquals(emptyList(), result.matchedRules)
        assertEquals(emptyList(), result.violations)
        assertNull(result.paymentTransactionId)
        assertNull(result.transactionEventId)
        assertNotNull(
            fixture.repositories.authorizationDecisions.findById(
                tenantId,
                AuthorizationDecisionId.of(result.authorizationDecisionId),
            ),
        )
    }

    @Test
    fun `all-owner rule applies to direct owner without a self-mandate`() {
        val fixture = fixture(actorId)
        val rule = fixture.addRule(
            id = 200,
            ownerId = actorId,
            mandateScope = MandateScope.AllOwnerMandates,
            amount = "5.00",
        )

        val result = fixture.service.authorizeTransaction(command(amount = "10.00"))

        assertEquals(AuthorizationDecision.DENIED, result.decision)
        assertEquals(rule.id.value, result.violations.single().limitRuleId)
    }

    @Test
    fun `periodic reservations accumulate and crossing the cap leaves usage unchanged`() {
        val fixture = fixture(firstOwnerId)
        fixture.addMandate(
            id = 100,
            ownerId = firstOwnerId,
            scope = FundingSourceScope.AllOwnerSources,
        )
        val rule = fixture.addRule(
            id = 200,
            ownerId = firstOwnerId,
            mandateScope = MandateScope.AllOwnerMandates,
            amount = "100.00",
            limitKind = LimitKind.Periodic(LimitPeriod.DAILY),
        )

        assertEquals(
            AuthorizationDecision.ALLOWED,
            fixture.service.authorizeTransaction(command(amount = "60.00")).decision,
        )
        assertEquals(
            AuthorizationDecision.ALLOWED,
            fixture.service.authorizeTransaction(command(amount = "40.00")).decision,
        )

        val denied = fixture.service.authorizeTransaction(command(amount = "0.01"))

        assertEquals(AuthorizationDecision.DENIED, denied.decision)
        assertEquals("PERIODIC_LIMIT_EXCEEDED", denied.violations.single().code)
        assertEquals(rule.id.value, denied.violations.single().limitRuleId)
        val bucket = fixture.periodicBucket(rule, LimitPeriod.DAILY)
        assertEquals(Money.positive("100.00", CurrencyCode.of("EUR")), bucket.reservedAmount)
        assertEquals(2, fixture.repositories.usageEvents.findByBucket(tenantId, bucket.id).size)
        assertEquals(2, fixture.repositories.transactions.findByFundingSourceId(tenantId, fundingSourceId).size)
    }

    @Test
    fun `authorization replay does not reserve periodic usage twice`() {
        val fixture = fixture(firstOwnerId)
        fixture.addMandate(
            id = 100,
            ownerId = firstOwnerId,
            scope = FundingSourceScope.AllOwnerSources,
        )
        val rule = fixture.addRule(
            id = 200,
            ownerId = firstOwnerId,
            mandateScope = MandateScope.AllOwnerMandates,
            amount = "100.00",
            limitKind = LimitKind.Periodic(LimitPeriod.DAILY),
        )

        val first = fixture.service.authorizeTransaction(command(amount = "60.00", idempotencyKey = "periodic-1"))
        val replay = fixture.service.authorizeTransaction(command(amount = "60.00", idempotencyKey = "periodic-1"))

        assertEquals(first, replay)
        val bucket = fixture.periodicBucket(rule, LimitPeriod.DAILY)
        assertEquals(Money.positive("60.00", CurrencyCode.of("EUR")), bucket.reservedAmount)
        assertEquals(1, fixture.repositories.usageEvents.findByBucket(tenantId, bucket.id).size)
    }

    @Test
    fun `cascading periodic rules reserve every matched bucket`() {
        val fixture = fixture(firstOwnerId)
        fixture.addMandate(
            id = 100,
            ownerId = firstOwnerId,
            scope = FundingSourceScope.AllOwnerSources,
        )
        val dailyRule = fixture.addRule(
            id = 200,
            ownerId = firstOwnerId,
            mandateScope = MandateScope.AllOwnerMandates,
            amount = "100.00",
            priority = 10,
            limitKind = LimitKind.Periodic(LimitPeriod.DAILY),
        )
        val monthlyRule = fixture.addRule(
            id = 201,
            ownerId = firstOwnerId,
            mandateScope = MandateScope.AllOwnerMandates,
            amount = "500.00",
            priority = 20,
            limitKind = LimitKind.Periodic(LimitPeriod.MONTHLY),
        )

        val result = fixture.service.authorizeTransaction(command(amount = "25.00"))

        assertEquals(AuthorizationDecision.ALLOWED, result.decision)
        val events = fixture.repositories.usageEvents.findByTransactionEvent(
            tenantId,
            TransactionEventId.of(requireNotNull(result.transactionEventId)),
        )
        assertEquals(setOf(dailyRule.id, monthlyRule.id), events.mapTo(mutableSetOf()) { it.limitRuleId })
    }

    @Test
    fun `isolated periodic rule stops broader owner usage but not per-transaction checks`() {
        val fixture = fixture(firstOwnerId)
        fixture.addMandate(
            id = 100,
            ownerId = firstOwnerId,
            scope = FundingSourceScope.AllOwnerSources,
        )
        val isolatedRule = fixture.addRule(
            id = 200,
            ownerId = firstOwnerId,
            mandateScope = MandateScope.AllOwnerMandates,
            amount = "1000.00",
            priority = 10,
            limitKind = LimitKind.Periodic(LimitPeriod.DAILY),
            usageConsumptionMode = LimitUsageConsumptionMode.ISOLATED,
        )
        val peerRule = fixture.addRule(
            id = 203,
            ownerId = firstOwnerId,
            mandateScope = MandateScope.AllOwnerMandates,
            amount = "2000.00",
            priority = 10,
            limitKind = LimitKind.Periodic(LimitPeriod.MONTHLY),
        )
        val broaderRule = fixture.addRule(
            id = 201,
            ownerId = firstOwnerId,
            mandateScope = MandateScope.AllOwnerMandates,
            amount = "5000.00",
            priority = 20,
            limitKind = LimitKind.Periodic(LimitPeriod.MONTHLY),
        )
        val perTransactionRule = fixture.addRule(
            id = 202,
            ownerId = firstOwnerId,
            mandateScope = MandateScope.AllOwnerMandates,
            amount = "50.00",
            priority = 30,
        )

        val allowed = fixture.service.authorizeTransaction(command(amount = "40.00"))

        assertEquals(AuthorizationDecision.ALLOWED, allowed.decision)
        assertEquals(
            listOf(isolatedRule.id.value, peerRule.id.value, perTransactionRule.id.value),
            allowed.matchedRules.map { it.id },
        )
        val allowedEvents = fixture.repositories.usageEvents.findByTransactionEvent(
            tenantId,
            TransactionEventId.of(
                requireNotNull(allowed.transactionEventId),
            ),
        )
        assertEquals(setOf(isolatedRule.id, peerRule.id), allowedEvents.mapTo(mutableSetOf()) { it.limitRuleId })
        assertEquals(
            emptyList(),
            fixture.repositories.usageBuckets.findByLimitRuleInWindow(
                tenantId,
                broaderRule.id,
                UsageWindowCalculator().calculateWindow(now, LimitPeriod.MONTHLY),
            ),
        )

        val denied = fixture.service.authorizeTransaction(command(amount = "60.00"))

        assertEquals(AuthorizationDecision.DENIED, denied.decision)
        assertEquals("PER_TRANSACTION_LIMIT_EXCEEDED", denied.violations.single().code)
        assertEquals(perTransactionRule.id.value, denied.violations.single().limitRuleId)
        assertEquals(
            Money.positive("40.00", CurrencyCode.of("EUR")),
            fixture.periodicBucket(isolatedRule, LimitPeriod.DAILY).reservedAmount,
        )
    }

    @Test
    fun `isolated rule from one co-owner does not suppress another owners usage`() {
        val fixture = fixture(firstOwnerId, secondOwnerId)
        val firstMandate = fixture.addMandate(
            id = 100,
            ownerId = firstOwnerId,
            scope = FundingSourceScope.AllOwnerSources,
        )
        val secondMandate = fixture.addMandate(
            id = 101,
            ownerId = secondOwnerId,
            scope = FundingSourceScope.AllOwnerSources,
        )
        val isolatedRule = fixture.addRule(
            id = 200,
            ownerId = firstOwnerId,
            mandateScope = MandateScope.MandateSpecific(firstMandate.id),
            amount = "100.00",
            priority = 10,
            limitKind = LimitKind.Periodic(LimitPeriod.DAILY),
            usageConsumptionMode = LimitUsageConsumptionMode.ISOLATED,
        )
        val otherOwnerRule = fixture.addRule(
            id = 201,
            ownerId = secondOwnerId,
            mandateScope = MandateScope.MandateSpecific(secondMandate.id),
            amount = "500.00",
            priority = 20,
            limitKind = LimitKind.Periodic(LimitPeriod.MONTHLY),
        )

        val result = fixture.service.authorizeTransaction(command(amount = "25.00"))

        assertEquals(AuthorizationDecision.ALLOWED, result.decision)
        val events = fixture.repositories.usageEvents.findByTransactionEvent(
            tenantId,
            TransactionEventId.of(requireNotNull(result.transactionEventId)),
        )
        assertEquals(setOf(isolatedRule.id, otherOwnerRule.id), events.mapTo(mutableSetOf()) { it.limitRuleId })
    }

    @Test
    fun `different-currency rule fails closed instead of being skipped`() {
        val fixture = fixture(firstOwnerId)
        fixture.addMandate(
            id = 100,
            ownerId = firstOwnerId,
            scope = FundingSourceScope.AllOwnerSources,
        )
        val rule = fixture.addRule(
            id = 200,
            ownerId = firstOwnerId,
            mandateScope = MandateScope.AllOwnerMandates,
            amount = "100.00",
        )

        val result = fixture.service.authorizeTransaction(command(amount = "10.00", currency = "USD"))

        assertEquals(AuthorizationDecision.DENIED, result.decision)
        assertEquals("LIMIT_CURRENCY_MISMATCH", result.violations.single().code)
        assertEquals(rule.id.value, result.violations.single().limitRuleId)
        assertEquals(emptyList(), fixture.repositories.transactions.findByFundingSourceId(tenantId, fundingSourceId))
    }

    private fun fixture(
        ownerId: PartyProfileId,
        additionalOwnerId: PartyProfileId? = null,
        noMatchingRuleBehavior: NoMatchingRuleBehavior = NoMatchingRuleBehavior.DENY,
    ): AuthorizationFixture =
        AuthorizationFixture(
            ownerIds = listOfNotNull(ownerId, additionalOwnerId),
            noMatchingRuleBehavior = noMatchingRuleBehavior,
        )

    private fun command(
        amount: String,
        idempotencyKey: String? = null,
        currency: String = "EUR",
        expiresAt: Instant? = null,
    ): AuthorizeTransactionCommand =
        AuthorizeTransactionCommand(
            tenantId = tenantId.value,
            actingPartyProfileId = actorId.value,
            fundingSourceId = fundingSourceId.value,
            operationTypeId = operationTypeId.value,
            channelId = channelId.value,
            amount = BigDecimal(amount),
            currency = currency,
            idempotencyKey = idempotencyKey,
            expiresAt = expiresAt,
            metadata = mapOf("merchantCategory" to "GROCERY"),
        )

    private fun captureCommand(
        paymentTransactionId: UUID,
        amount: String? = null,
        reason: String? = null,
        idempotencyKey: String? = null,
        metadata: Map<String, Any?> = emptyMap(),
    ): CaptureTransactionCommand =
        CaptureTransactionCommand(
            tenantId = tenantId.value,
            paymentTransactionId = paymentTransactionId,
            amount = amount?.toBigDecimal(),
            reason = reason,
            idempotencyKey = idempotencyKey,
            metadata = metadata,
        )

    private fun releaseCommand(
        paymentTransactionId: UUID,
        amount: String? = null,
        reason: String? = null,
        idempotencyKey: String? = null,
        metadata: Map<String, Any?> = emptyMap(),
    ): ReleaseAuthorizationCommand =
        ReleaseAuthorizationCommand(
            tenantId = tenantId.value,
            paymentTransactionId = paymentTransactionId,
            amount = amount?.toBigDecimal(),
            reason = reason,
            idempotencyKey = idempotencyKey,
            metadata = metadata,
        )

    private fun reverseCommand(
        paymentTransactionId: UUID,
        amount: String? = null,
        reason: String? = null,
        idempotencyKey: String? = null,
        metadata: Map<String, Any?> = emptyMap(),
    ): ReverseTransactionCommand =
        ReverseTransactionCommand(
            tenantId = tenantId.value,
            paymentTransactionId = paymentTransactionId,
            amount = amount?.toBigDecimal(),
            reason = reason,
            idempotencyKey = idempotencyKey,
            metadata = metadata,
        )

    private inner class AuthorizationFixture(
        ownerIds: List<PartyProfileId>,
        noMatchingRuleBehavior: NoMatchingRuleBehavior,
    ) {
        val repositories = InMemoryLimitguardRepositories()
        val service: DefaultLimitEvaluationService
        val clock = FixedClockProvider(now)

        init {
            repositories.tenants.insert(
                Tenant(
                    id = tenantId,
                    name = TenantName.of("Test Bank"),
                    noMatchingRuleBehavior = noMatchingRuleBehavior,
                    createdAt = now,
                ),
            )
            (ownerIds + actorId)
                .distinct()
                .forEach(::addPartyProfile)
            repositories.channels.insert(
                tenantId,
                Channel(
                    id = channelId,
                    tenantId = tenantId,
                    code = ChannelCode.of("MOBILE"),
                    name = ChannelName.of("Mobile"),
                    isActive = true,
                    createdAt = now,
                ),
            )
            repositories.operationTypes.insert(
                tenantId,
                OperationType(
                    id = operationTypeId,
                    scope = OperationTypeScope.TenantSpecific(tenantId),
                    parentId = null,
                    code = OperationTypeCode.of("TRANSFER"),
                    name = OperationTypeName.of("Transfer"),
                    isLeaf = true,
                    createdAt = now,
                ),
            )
            repositories.fundingSourceProducts.insert(
                tenantId,
                FundingSourceProduct(
                    id = FundingSourceProductId.of(uuid(20)),
                    tenantId = tenantId,
                    parentId = null,
                    accountCategory = FundingSourceCategory.TRANSACTIONAL,
                    nodeType = ProductNodeType.PRODUCT,
                    code = FundingSourceProductCode.of("CURRENT_ACCOUNT"),
                    name = FundingSourceProductName.of("Current account"),
                    isLeaf = true,
                    isActive = true,
                    createdAt = now,
                ),
            )
            repositories.fundingSources.insert(
                tenantId,
                FundingSource(
                    id = fundingSourceId,
                    tenantId = tenantId,
                    productId = FundingSourceProductId.of(uuid(20)),
                    isActive = true,
                    createdAt = now,
                ),
            )
            ownerIds.forEachIndexed { index, ownerId ->
                repositories.fundingSourceOwners.insert(
                    tenantId,
                    FundingSourceOwner(
                        id = FundingSourceOwnerId.of(uuid(30 + index.toLong())),
                        tenantId = tenantId,
                        partyProfileId = ownerId,
                        fundingSourceId = fundingSourceId,
                        ownerRole = if (index == 0) {
                            FundingSourceOwnerRole.PRIMARY_OWNER
                        } else {
                            FundingSourceOwnerRole.JOINT_OWNER
                        },
                        createdAt = now,
                    ),
                )
            }
            val idGenerator = SequentialIdGenerator(startAt = 1000L)
            val transactionRunner = DirectTransactionRunner()
            val transactionRecorder = TransactionRecorder(
                transactionRepository = repositories.transactions,
                transactionEventRepository = repositories.transactionEvents,
                idGenerator = idGenerator,
            )
            val usageMutationService = UsageMutationService(
                idGenerator = idGenerator,
                transactionEventRepository = repositories.transactionEvents,
                usageBucketRepository = repositories.usageBuckets,
                usageEventRepository = repositories.usageEvents,
            )
            service = DefaultLimitEvaluationService(
                authorizationDecisionRecorder = AuthorizationDecisionRecorder(
                    repository = repositories.authorizationDecisions,
                    idGenerator = idGenerator,
                ),
                transactionRepository = repositories.transactions,
                transactionEventRepository = repositories.transactionEvents,
                fundingSourceOwnerRepository = repositories.fundingSourceOwners,
                authorizationReferenceResolver = AuthorizationReferenceResolver(
                    tenantRepository = repositories.tenants,
                    channelRepository = repositories.channels,
                    operationTypeRepository = repositories.operationTypes,
                    partyProfileRepository = repositories.partyProfiles,
                    fundingSourceRepository = repositories.fundingSources,
                    fundingSourceProductRepository = repositories.fundingSourceProducts,
                ),
                transactionFactory = TransactionFactory(idGenerator),
                mandateResolver = MandateResolver(
                    mandateRepository = repositories.mandates,
                    fundingSourceGroupMemberRepository = repositories.fundingSourceGroupMembers,
                ),
                limitRuleMatcher = LimitRuleMatcher(
                    limitRuleRepository = repositories.limitRules,
                    channelGroupMemberRepository = repositories.channelGroupMembers,
                ),
                authorizationEvaluator = AuthorizationEvaluator(
                    UsageReservationService(
                        idGenerator = idGenerator,
                        usageBucketRepository = repositories.usageBuckets,
                        usageEventRepository = repositories.usageEvents,
                        usageWindowCalculator = UsageWindowCalculator(),
                    ),
                ),
                usageMutationService = usageMutationService,
                transactionRecorder = transactionRecorder,
                expireDueAuthorizationsWorkflow = ExpireDueAuthorizationsWorkflow(
                    tenantRepository = repositories.tenants,
                    transactionRepository = repositories.transactions,
                    usageMutationService = usageMutationService,
                    transactionRecorder = transactionRecorder,
                    clockProvider = clock,
                    transactionRunner = transactionRunner,
                ),
                clockProvider = clock,
                transactionRunner = transactionRunner,
            )
        }

        fun addMandate(
            id: Long,
            ownerId: PartyProfileId,
            scope: FundingSourceScope,
            isActive: Boolean = true,
        ): Mandate =
            Mandate(
                id = MandateId.of(uuid(id)),
                tenantId = tenantId,
                ownerPartyProfileId = ownerId,
                assigneePartyProfileId = actorId,
                authorityDelegationId = null,
                name = MandateName.of("Mandate $id"),
                description = null,
                fundingScope = scope,
                isActive = isActive,
                version = Version.INITIAL,
                effectiveWindow = EffectiveWindow(now.minusSeconds(60), null),
                metadata = Metadata.EMPTY,
                createdAt = now,
                updatedAt = now,
            ).also { repositories.mandates.insert(tenantId, it) }

        fun addRule(
            id: Long,
            ownerId: PartyProfileId,
            mandateScope: MandateScope,
            amount: String,
            priority: Int = 100,
            channelScope: LimitChannelScope = LimitChannelScope.AllChannels,
            limitKind: LimitKind = LimitKind.PerTransaction,
            usageConsumptionMode: LimitUsageConsumptionMode = LimitUsageConsumptionMode.CASCADING,
        ): LimitRule =
            LimitRule(
                id = LimitRuleId.of(uuid(id)),
                tenantId = tenantId,
                ownerPartyProfileId = ownerId,
                name = LimitRuleName.of("Rule $id"),
                description = null,
                limitKind = limitKind,
                amount = Money.positive(amount, CurrencyCode.of("EUR")),
                mandateScope = mandateScope,
                configurationId = null,
                channelScope = channelScope,
                operationScope = LimitOperationScope.OperationTypeSpecific(operationTypeId),
                usageConsumptionMode = usageConsumptionMode,
                priority = LimitPriority.of(priority),
                isActive = true,
                version = Version.INITIAL,
                effectiveWindow = EffectiveWindow(now.minusSeconds(60), null),
                metadata = Metadata.EMPTY,
                createdAt = now,
                updatedAt = now,
            ).also { repositories.limitRules.insert(tenantId, it) }

        fun periodicBucket(rule: LimitRule, period: LimitPeriod) =
            repositories.usageBuckets.findByLimitRuleAndAssigneeInWindow(
                tenantId = tenantId,
                limitRuleId = rule.id,
                assigneeId = actorId,
                currency = rule.amount.currency,
                window = UsageWindowCalculator().calculateWindow(now, period),
            ).let(::assertNotNull)

        private fun addPartyProfile(profileId: PartyProfileId) {
            repositories.partyProfiles.insert(
                tenantId,
                PartyProfile(
                    id = profileId,
                    partyId = PartyId.of(UUID(profileId.value.mostSignificantBits, profileId.value.leastSignificantBits + 1000)),
                    tenantId = tenantId,
                    createdAt = now,
                ),
            )
        }
    }

    private companion object {
        fun uuid(value: Long): UUID = UUID(0L, value)
    }
}
