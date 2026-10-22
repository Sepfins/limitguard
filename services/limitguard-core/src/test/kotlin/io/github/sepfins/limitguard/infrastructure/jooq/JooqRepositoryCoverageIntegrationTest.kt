package io.github.sepfins.limitguard.infrastructure.jooq

import io.github.sepfins.limitguard.domain.limit.LimitAmount
import io.github.sepfins.limitguard.api.TrustedChangeActorContext
import io.github.sepfins.limitguard.api.commands.ApplyDueLimitChangesCommand
import io.github.sepfins.limitguard.api.commands.RegisterFundingSourceProductCommand
import io.github.sepfins.limitguard.api.commands.RegisterTenantCommand
import io.github.sepfins.limitguard.api.commands.ReleaseAuthorizationCommand
import io.github.sepfins.limitguard.api.errors.IdempotencyConflictException
import io.github.sepfins.limitguard.api.queries.ListFundingSourceProductsQuery
import io.github.sepfins.limitguard.api.queries.ListTenantsQuery
import io.github.sepfins.limitguard.application.transaction.TransactionMutationKind
import io.github.sepfins.limitguard.application.usage.UsageMutationService
import io.github.sepfins.limitguard.application.usage.UsageReservationService
import io.github.sepfins.limitguard.application.usage.UsageWindowCalculator
import io.github.sepfins.limitguard.domain.change.ChangeActor
import io.github.sepfins.limitguard.domain.change.LimitRuleChangeEventType
import io.github.sepfins.limitguard.domain.change.LimitRuleChangeRequest
import io.github.sepfins.limitguard.domain.change.LimitRuleChangeStatus
import io.github.sepfins.limitguard.domain.change.LimitRuleChangeType
import io.github.sepfins.limitguard.domain.change.LimitRuleRevision
import io.github.sepfins.limitguard.domain.change.LimitRuleRevisionEventType
import io.github.sepfins.limitguard.domain.change.RequestActorContext
import io.github.sepfins.limitguard.domain.change.RevisionNumber
import io.github.sepfins.limitguard.domain.channel.Channel
import io.github.sepfins.limitguard.domain.channel.ChannelCode
import io.github.sepfins.limitguard.domain.channel.ChannelName
import io.github.sepfins.limitguard.domain.common.ChannelId
import io.github.sepfins.limitguard.domain.common.CurrencyCode
import io.github.sepfins.limitguard.domain.common.EffectiveWindow
import io.github.sepfins.limitguard.domain.common.ExternalReference
import io.github.sepfins.limitguard.domain.common.FundingSourceGroupId
import io.github.sepfins.limitguard.domain.common.FundingSourceId
import io.github.sepfins.limitguard.domain.common.FundingSourceOwnerId
import io.github.sepfins.limitguard.domain.common.FundingSourceProductId
import io.github.sepfins.limitguard.domain.common.IdempotencyKey
import io.github.sepfins.limitguard.domain.common.LimitChangeRequestId
import io.github.sepfins.limitguard.domain.common.LimitConfigurationId
import io.github.sepfins.limitguard.domain.common.LimitRuleId
import io.github.sepfins.limitguard.domain.common.LimitRuleRevisionId
import io.github.sepfins.limitguard.domain.common.Metadata
import io.github.sepfins.limitguard.domain.common.Money
import io.github.sepfins.limitguard.domain.common.OperationTypeId
import io.github.sepfins.limitguard.domain.common.PartyId
import io.github.sepfins.limitguard.domain.common.PartyProfileDelegationId
import io.github.sepfins.limitguard.domain.common.PartyProfileId
import io.github.sepfins.limitguard.domain.common.PaymentTransactionId
import io.github.sepfins.limitguard.domain.common.TenantId
import io.github.sepfins.limitguard.domain.common.TransactionEventId
import io.github.sepfins.limitguard.domain.common.UsageBucketId
import io.github.sepfins.limitguard.domain.common.UsageEventId
import io.github.sepfins.limitguard.domain.common.UsageWindow
import io.github.sepfins.limitguard.domain.common.Version
import io.github.sepfins.limitguard.domain.funding.FundingSource
import io.github.sepfins.limitguard.domain.funding.FundingSourceCategory
import io.github.sepfins.limitguard.domain.funding.FundingSourceGroup
import io.github.sepfins.limitguard.domain.funding.FundingSourceGroupCode
import io.github.sepfins.limitguard.domain.funding.FundingSourceGroupMember
import io.github.sepfins.limitguard.domain.funding.FundingSourceGroupName
import io.github.sepfins.limitguard.domain.funding.FundingSourceOwner
import io.github.sepfins.limitguard.domain.funding.FundingSourceProduct
import io.github.sepfins.limitguard.domain.funding.FundingSourceProductCode
import io.github.sepfins.limitguard.domain.funding.FundingSourceProductName
import io.github.sepfins.limitguard.domain.funding.ProductNodeType
import io.github.sepfins.limitguard.domain.limit.LimitChannelScope
import io.github.sepfins.limitguard.domain.limit.LimitConfiguration
import io.github.sepfins.limitguard.domain.limit.LimitConfigurationCode
import io.github.sepfins.limitguard.domain.limit.LimitConfigurationFundingScope
import io.github.sepfins.limitguard.domain.limit.LimitConfigurationName
import io.github.sepfins.limitguard.domain.limit.LimitKind
import io.github.sepfins.limitguard.domain.limit.LimitOperationScope
import io.github.sepfins.limitguard.domain.limit.LimitPeriod
import io.github.sepfins.limitguard.domain.limit.LimitPriority
import io.github.sepfins.limitguard.domain.limit.LimitRule
import io.github.sepfins.limitguard.application.evaluation.ApplicableLimitRule
import io.github.sepfins.limitguard.domain.limit.LimitUsageConsumptionMode
import io.github.sepfins.limitguard.domain.mandate.MandateScope
import io.github.sepfins.limitguard.domain.operation.OperationType
import io.github.sepfins.limitguard.domain.operation.OperationTypeCode
import io.github.sepfins.limitguard.domain.operation.OperationTypeName
import io.github.sepfins.limitguard.domain.operation.OperationTypeScope
import io.github.sepfins.limitguard.domain.party.FirstName
import io.github.sepfins.limitguard.domain.party.Individual
import io.github.sepfins.limitguard.domain.party.LastName
import io.github.sepfins.limitguard.domain.party.Party
import io.github.sepfins.limitguard.domain.party.PartyProfile
import io.github.sepfins.limitguard.domain.party.PartyProfileDelegation
import io.github.sepfins.limitguard.domain.party.PartyProfileDelegationType
import io.github.sepfins.limitguard.domain.party.PartyType
import io.github.sepfins.limitguard.domain.tenant.Tenant
import io.github.sepfins.limitguard.domain.tenant.TenantName
import io.github.sepfins.limitguard.domain.transaction.PaymentTransaction
import io.github.sepfins.limitguard.domain.transaction.TransactionEvent
import io.github.sepfins.limitguard.domain.transaction.TransactionEventReason
import io.github.sepfins.limitguard.domain.transaction.TransactionEventType
import io.github.sepfins.limitguard.domain.transaction.TransactionStatus
import io.github.sepfins.limitguard.domain.usage.LimitUsageBucket
import io.github.sepfins.limitguard.domain.usage.LimitUsageEvent
import io.github.sepfins.limitguard.domain.usage.LimitUsageEventType
import io.github.sepfins.limitguard.domain.usage.UsageMutation
import io.github.sepfins.limitguard.infrastructure.jooq.repository.JooqChannelRepository
import io.github.sepfins.limitguard.infrastructure.jooq.repository.JooqFundingSourceGroupMemberRepository
import io.github.sepfins.limitguard.infrastructure.jooq.repository.JooqFundingSourceGroupRepository
import io.github.sepfins.limitguard.infrastructure.jooq.repository.JooqFundingSourceOwnerRepository
import io.github.sepfins.limitguard.infrastructure.jooq.repository.JooqFundingSourceProductRepository
import io.github.sepfins.limitguard.infrastructure.jooq.repository.JooqFundingSourceRepository
import io.github.sepfins.limitguard.infrastructure.jooq.repository.JooqIndividualRepository
import io.github.sepfins.limitguard.infrastructure.jooq.repository.JooqLimitChangeRequestRepository
import io.github.sepfins.limitguard.infrastructure.jooq.repository.JooqLimitConfigurationRepository
import io.github.sepfins.limitguard.infrastructure.jooq.repository.JooqLimitRuleChangeEventRepository
import io.github.sepfins.limitguard.infrastructure.jooq.repository.JooqLimitRuleRepository
import io.github.sepfins.limitguard.infrastructure.jooq.repository.JooqLimitRuleRevisionRepository
import io.github.sepfins.limitguard.infrastructure.jooq.repository.JooqOperationTypeRepository
import io.github.sepfins.limitguard.infrastructure.jooq.repository.JooqPartyProfileDelegationRepository
import io.github.sepfins.limitguard.infrastructure.jooq.repository.JooqPartyProfileRepository
import io.github.sepfins.limitguard.infrastructure.jooq.repository.JooqPartyRepository
import io.github.sepfins.limitguard.infrastructure.jooq.repository.JooqTenantRepository
import io.github.sepfins.limitguard.infrastructure.jooq.repository.JooqTransactionEventRepository
import io.github.sepfins.limitguard.infrastructure.jooq.repository.JooqTransactionRepository
import io.github.sepfins.limitguard.infrastructure.jooq.repository.JooqUsageBucketRepository
import io.github.sepfins.limitguard.infrastructure.jooq.repository.JooqUsageEventRepository
import io.github.sepfins.limitguard.infrastructure.ids.SystemIdGenerator
import io.github.sepfins.limitguard.port.persistence.UsageBucketRepository
import org.flywaydb.core.Flyway
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import java.math.BigDecimal
import java.math.BigInteger
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@Testcontainers
class JooqRepositoryCoverageIntegrationTest {
    private val dataSource: PGSimpleDataSource by lazy {
        PGSimpleDataSource().apply {
            setURL(postgres.jdbcUrl)
            user = postgres.username
            password = postgres.password
        }
    }
    private val context by lazy { JooqPersistenceContext(dataSource) }

    private val tenants by lazy { JooqTenantRepository(context) }
    private val parties by lazy { JooqPartyRepository(context) }
    private val individuals by lazy { JooqIndividualRepository(context) }
    private val partyProfiles by lazy { JooqPartyProfileRepository(context) }
    private val delegations by lazy { JooqPartyProfileDelegationRepository(context) }
    private val channels by lazy { JooqChannelRepository(context) }
    private val operationTypes by lazy { JooqOperationTypeRepository(context) }
    private val products by lazy { JooqFundingSourceProductRepository(context) }
    private val fundingSources by lazy { JooqFundingSourceRepository(context) }
    private val fundingOwners by lazy { JooqFundingSourceOwnerRepository(context) }
    private val fundingGroups by lazy { JooqFundingSourceGroupRepository(context) }
    private val fundingGroupMembers by lazy { JooqFundingSourceGroupMemberRepository(context) }
    private val configurations by lazy { JooqLimitConfigurationRepository(context) }
    private val rules by lazy { JooqLimitRuleRepository(context) }
    private val changeRequests by lazy { JooqLimitChangeRequestRepository(context) }
    private val changeEvents by lazy { JooqLimitRuleChangeEventRepository(context) }
    private val revisions by lazy { JooqLimitRuleRevisionRepository(context) }
    private val transactions by lazy { JooqTransactionRepository(context) }
    private val transactionEvents by lazy { JooqTransactionEventRepository(context) }
    private val usageBuckets by lazy { JooqUsageBucketRepository(context) }
    private val usageEvents by lazy { JooqUsageEventRepository(context) }

    @BeforeEach
    fun resetDatabase() {
        DSL.using(dataSource, SQLDialect.POSTGRES).execute(
            "truncate table tenant, party, operation_type cascade"
        )
    }

    @Test
    fun `factory exposes database backed catalog paging filtering and literal search`() {
        val limitguard = JooqLimitguardFactory(dataSource).create()
        val zulu = limitguard.tenantCatalogService.registerTenant(RegisterTenantCommand(name = "Zulu Bank"))
        val alpha = limitguard.tenantCatalogService.registerTenant(RegisterTenantCommand(name = "Alpha Bank"))
        limitguard.tenantCatalogService.registerTenant(RegisterTenantCommand(name = "Percent% Bank"))

        val tenantPage = limitguard.tenantCatalogQueryService.listTenants(
            ListTenantsQuery(page = 0, size = 2),
        )
        assertEquals(listOf("Alpha Bank", "Percent% Bank"), tenantPage.items.map { it.name })
        assertEquals(3, tenantPage.totalElements)
        assertEquals(2, tenantPage.totalPages)
        assertEquals(
            listOf("Zulu Bank"),
            limitguard.tenantCatalogQueryService.listTenants(ListTenantsQuery(page = 1, size = 2))
                .items.map { it.name },
        )
        assertEquals(
            listOf("Percent% Bank"),
            limitguard.tenantCatalogQueryService.listTenants(ListTenantsQuery(search = "% bAnK"))
                .items
                .map { it.name },
        )

        limitguard.tenantCatalogService.registerFundingSourceProduct(
            RegisterFundingSourceProductCommand(
                tenantId = alpha.id,
                code = "CARD",
                name = "Payment Card",
                category = io.github.sepfins.limitguard.api.FundingSourceCategory.TRANSACTIONAL,
                nodeType = io.github.sepfins.limitguard.api.ProductNodeType.PRODUCT,
                isLeaf = true,
            ),
        )
        limitguard.tenantCatalogService.registerFundingSourceProduct(
            RegisterFundingSourceProductCommand(
                tenantId = alpha.id,
                code = "PERCENT",
                name = "Percent% Product",
                category = io.github.sepfins.limitguard.api.FundingSourceCategory.SAVINGS,
                nodeType = io.github.sepfins.limitguard.api.ProductNodeType.PRODUCT,
                isLeaf = true,
                isActive = false,
            ),
        )
        limitguard.tenantCatalogService.registerFundingSourceProduct(
            RegisterFundingSourceProductCommand(
                tenantId = zulu.id,
                code = "CARD",
                name = "Other Tenant Card",
                category = io.github.sepfins.limitguard.api.FundingSourceCategory.TRANSACTIONAL,
                nodeType = io.github.sepfins.limitguard.api.ProductNodeType.PRODUCT,
                isLeaf = true,
            ),
        )

        assertEquals(
            listOf("CARD", "PERCENT"),
            limitguard.tenantCatalogQueryService.listFundingSourceProducts(
                ListFundingSourceProductsQuery(tenantId = alpha.id),
            ).items.map { it.code },
        )
        val inactiveLiteralMatch = limitguard.tenantCatalogQueryService.listFundingSourceProducts(
            ListFundingSourceProductsQuery(
                tenantId = alpha.id,
                search = "%",
                active = false,
            ),
        )
        assertEquals(listOf("PERCENT"), inactiveLiteralMatch.items.map { it.code })
        assertEquals(1, inactiveLiteralMatch.totalElements)
    }

    @Test
    fun `postgres JSON number normalization preserves mutation replay intent`() {
        val fixture = seedFunding()
        val transaction = transactions.insert(
            fixture.foundation.tenant.id,
            PaymentTransaction(
                id = PaymentTransactionId.of(UUID.randomUUID()),
                tenantId = fixture.foundation.tenant.id,
                actingPartyProfileId = fixture.foundation.ownerProfile.id,
                fundingSourceId = fixture.fundingSource.id,
                operationTypeId = fixture.foundation.operation.id,
                channelId = fixture.foundation.channel.id,
                authorizedAmount = Money.positive("200", EUR),
                capturedAmount = Money.zero(EUR),
                status = TransactionStatus.AUTHORIZED,
                metadata = Metadata.EMPTY,
                createdAt = NOW,
            ),
        )
        val authorizationEvent = transactionEvents.insert(
            fixture.foundation.tenant.id,
            TransactionEvent(
                id = TransactionEventId.of(UUID.randomUUID()),
                tenantId = fixture.foundation.tenant.id,
                paymentTransactionId = transaction.id,
                eventType = TransactionEventType.AUTHORIZATION,
                amount = transaction.authorizedAmount,
                createdAt = NOW,
            ),
        )
        val metadata = mapOf(
            "attempt" to 1L,
            "optional" to null,
            "nested" to mapOf(
                "optional" to null,
                "items" to listOf(
                    null,
                    1L,
                    BigInteger("9007199254740993"),
                    BigDecimal("0.123456789012345678901234567890"),
                ),
            ),
        )
        val limitguard = JooqLimitguardFactory(dataSource).create()
        val command = ReleaseAuthorizationCommand(
            tenantId = fixture.foundation.tenant.id.value,
            paymentTransactionId = transaction.id.value,
            idempotencyKey = "metadata-number-replay",
            metadata = metadata,
        )

        val first = limitguard.limitEvaluationService.releaseAuthorization(command)
        val eventId = TransactionEventId.of(requireNotNull(first.transactionEventId))
        val persistedEvent = assertNotNull(
            transactionEvents.findById(fixture.foundation.tenant.id, eventId),
        )
        assertTrue(persistedEvent.metadata.value["attempt"] is Int)
        assertEquals(1, persistedEvent.metadata.value["attempt"])
        val eventCount = transactionEvents.findByTransaction(
            fixture.foundation.tenant.id,
            transaction.id,
        ).size

        val replay = limitguard.limitEvaluationService.releaseAuthorization(command)

        assertEquals(first, replay)
        assertEquals(
            eventCount,
            transactionEvents.findByTransaction(fixture.foundation.tenant.id, transaction.id).size,
        )
        assertFailsWith<IdempotencyConflictException> {
            limitguard.limitEvaluationService.releaseAuthorization(
                command.copy(
                    metadata = metadata + (
                        "nested" to mapOf(
                            "items" to listOf(
                                1L,
                                BigInteger("9007199254740994"),
                                BigDecimal("0.123456789012345678901234567890"),
                            ),
                        )
                    ),
                ),
            )
        }
        assertEquals(
            eventCount,
            transactionEvents.findByTransaction(fixture.foundation.tenant.id, transaction.id).size,
        )
        assertEquals(
            TransactionStatus.VOIDED,
            assertNotNull(transactions.findById(fixture.foundation.tenant.id, transaction.id)).status,
        )
        assertEquals(
            authorizationEvent,
            transactionEvents.findAuthorizationEvent(fixture.foundation.tenant.id, transaction.id),
        )
    }

    @Test
    fun `reservation and mutation acquire shared logical bucket order under postgres contention`() {
        val fixture = seedFunding()
        fun orderingRule(id: String, priority: Int): ApplicableLimitRule {
            val configuration = requireNotNull(configurations.findById(
                fixture.foundation.tenant.id, ruleConfiguration(fixture.foundation, priority = priority),
            ))
            val rule = rules.insert(
                fixture.foundation.tenant.id,
                limitRule(fixture.foundation, configuration.id).copy(
                    id = LimitRuleId.of(UUID.fromString(id)),
                    amount = LimitAmount.Bounded(Money.positive("1000", EUR)),
                ),
            )
            return ApplicableLimitRule(rule, configuration)
        }
        val lowPriority = orderingRule("00000000-0000-0000-0000-000000000101", 20)
        val highPriority = orderingRule("00000000-0000-0000-0000-000000000102", 10)
        val missingBucket = orderingRule("00000000-0000-0000-0000-000000000103", 30)
        val lowPriorityRule = lowPriority.rule
        val highPriorityRule = highPriority.rule
        val missingBucketRule = missingBucket.rule
        val transaction = transactions.insert(
            fixture.foundation.tenant.id,
            PaymentTransaction(
                id = PaymentTransactionId.of(UUID.fromString("00000000-0000-0000-0000-000000000301")),
                tenantId = fixture.foundation.tenant.id,
                actingPartyProfileId = fixture.foundation.ownerProfile.id,
                fundingSourceId = fixture.fundingSource.id,
                operationTypeId = fixture.foundation.operation.id,
                channelId = fixture.foundation.channel.id,
                authorizedAmount = Money.positive("10", EUR),
                capturedAmount = Money.zero(EUR),
                status = TransactionStatus.AUTHORIZED,
                createdAt = NOW,
            ),
        )
        val authorizationEvent = transactionEvents.insert(
            fixture.foundation.tenant.id,
            TransactionEvent(
                id = TransactionEventId.of(UUID.fromString("00000000-0000-0000-0000-000000000401")),
                tenantId = fixture.foundation.tenant.id,
                paymentTransactionId = transaction.id,
                eventType = TransactionEventType.AUTHORIZATION,
                amount = transaction.authorizedAmount,
                requestedAmount = transaction.authorizedAmount,
                reason = TransactionEventReason.of("Approved"),
                createdAt = NOW,
            ),
        )
        val reservationEvent = transactionEvents.insert(
            fixture.foundation.tenant.id,
            TransactionEvent(
                id = TransactionEventId.of(UUID.fromString("00000000-0000-0000-0000-000000000402")),
                tenantId = fixture.foundation.tenant.id,
                paymentTransactionId = transaction.id,
                eventType = TransactionEventType.ADJUSTMENT,
                amount = transaction.authorizedAmount,
                reason = TransactionEventReason.of("Reservation mutation"),
                createdAt = NOW,
            ),
        )
        val mutationEvent = transactionEvents.insert(
            fixture.foundation.tenant.id,
            TransactionEvent(
                id = TransactionEventId.of(UUID.fromString("00000000-0000-0000-0000-000000000403")),
                tenantId = fixture.foundation.tenant.id,
                paymentTransactionId = transaction.id,
                eventType = TransactionEventType.ADJUSTMENT,
                amount = Money.positive("1", EUR),
                reason = TransactionEventReason.of("Release mutation"),
                createdAt = NOW,
            ),
        )
        val window = UsageWindow(NOW, NOW.plus(1, ChronoUnit.DAYS))
        val lowPriorityBucket = usageBuckets.insert(
            fixture.foundation.tenant.id,
            LimitUsageBucket(
                id = UsageBucketId.of(UUID.fromString("00000000-0000-0000-0000-000000000201")),
                tenantId = fixture.foundation.tenant.id,
                limitRuleId = lowPriorityRule.id,
                assigneePartyProfileId = fixture.foundation.ownerProfile.id,
                window = window,
                reservedAmount = Money.positive("10", EUR),
                consumedAmount = Money.zero(EUR),
                createdAt = NOW,
                updatedAt = NOW,
            ),
        )
        val highPriorityBucket = usageBuckets.insert(
            fixture.foundation.tenant.id,
            LimitUsageBucket(
                id = UsageBucketId.of(UUID.fromString("00000000-0000-0000-0000-000000000202")),
                tenantId = fixture.foundation.tenant.id,
                limitRuleId = highPriorityRule.id,
                assigneePartyProfileId = fixture.foundation.ownerProfile.id,
                window = window,
                reservedAmount = Money.positive("10", EUR),
                consumedAmount = Money.zero(EUR),
                createdAt = NOW,
                updatedAt = NOW,
            ),
        )
        usageEvents.insert(
            fixture.foundation.tenant.id,
            LimitUsageEvent(
                id = UsageEventId.of(UUID.fromString("00000000-0000-0000-0000-000000000501")),
                tenantId = fixture.foundation.tenant.id,
                limitRuleId = lowPriorityRule.id,
                usageBucketId = lowPriorityBucket.id,
                transactionEventId = authorizationEvent.id,
                assigneePartyProfileId = fixture.foundation.ownerProfile.id,
                eventType = LimitUsageEventType.RESERVE,
                amountDelta = BigDecimal("10"),
                currency = EUR,
                createdAt = NOW,
            ),
        )
        usageEvents.insert(
            fixture.foundation.tenant.id,
            LimitUsageEvent(
                id = UsageEventId.of(UUID.fromString("00000000-0000-0000-0000-000000000502")),
                tenantId = fixture.foundation.tenant.id,
                limitRuleId = highPriorityRule.id,
                usageBucketId = highPriorityBucket.id,
                transactionEventId = authorizationEvent.id,
                assigneePartyProfileId = fixture.foundation.ownerProfile.id,
                eventType = LimitUsageEventType.RESERVE,
                amountDelta = BigDecimal("10"),
                currency = EUR,
                createdAt = NOW,
            ),
        )

        val reservationContext = JooqPersistenceContext(dataSource)
        val reservationRunner = JooqTransactionRunner(reservationContext)
        val reservationReachedFirstLock = CountDownLatch(1)
        val releaseReservation = CountDownLatch(1)
        val reservationLockRuleIds = CopyOnWriteArrayList<LimitRuleId>()
        val reservationBuckets = BucketLockBarrierRepository(
            delegate = JooqUsageBucketRepository(reservationContext),
            onFindForUpdate = { reservationLockRuleIds += it },
            afterFirstLock = {
                reservationReachedFirstLock.countDown()
                check(releaseReservation.await(5, TimeUnit.SECONDS)) {
                    "reservation did not receive its release signal"
                }
            },
        )
        val reservationService = UsageReservationService(
            idGenerator = SystemIdGenerator(),
            usageBucketRepository = reservationBuckets,
            usageEventRepository = JooqUsageEventRepository(reservationContext),
            usageWindowCalculator = UsageWindowCalculator(),
        )

        val mutationContext = JooqPersistenceContext(dataSource)
        val mutationRunner = JooqTransactionRunner(mutationContext)
        val mutationStarted = CountDownLatch(1)
        val mutationAttemptedFirstLock = CountDownLatch(1)
        val mutationAcquiredFirstLock = CountDownLatch(1)
        val mutationBuckets = BucketLockBarrierRepository(
            delegate = JooqUsageBucketRepository(mutationContext),
            beforeFirstLock = { mutationAttemptedFirstLock.countDown() },
            afterFirstLock = { mutationAcquiredFirstLock.countDown() },
        )
        val mutationService = UsageMutationService(
            idGenerator = SystemIdGenerator(),
            transactionEventRepository = JooqTransactionEventRepository(mutationContext),
            usageBucketRepository = mutationBuckets,
            usageEventRepository = JooqUsageEventRepository(mutationContext),
        )
        val executor = Executors.newFixedThreadPool(2)

        try {
            val reservationFuture = executor.submit<UsageMutation> {
                reservationRunner.inTransaction {
                    val plan = reservationService.planAuthorizationReservation(
                        transaction = transaction,
                        // This is evaluation/priority order; the lock key order must not depend on it.
                        periodicRules = listOf(highPriority, lowPriority, missingBucket),
                        now = NOW,
                    )
                    assertTrue(plan.allowed)
                    assertEquals(
                        listOf(highPriorityRule.id, lowPriorityRule.id, missingBucketRule.id),
                        plan.candidates.map { it.rule.id },
                    )
                    reservationService.reserve(plan, reservationEvent.id, NOW)
                }
            }
            assertTrue(reservationReachedFirstLock.await(5, TimeUnit.SECONDS))

            val mutationFuture = executor.submit<UsageMutation> {
                mutationRunner.inTransaction {
                    mutationStarted.countDown()
                    mutationService.mutate(
                        transaction = transaction,
                        mutationKind = TransactionMutationKind.RELEASE,
                        mutationEventId = mutationEvent.id,
                        amount = Money.positive("1", EUR),
                        now = NOW,
                    )
                }
            }
            assertTrue(mutationStarted.await(5, TimeUnit.SECONDS))
            assertTrue(mutationAttemptedFirstLock.await(5, TimeUnit.SECONDS))
            assertFalse(mutationAcquiredFirstLock.await(500, TimeUnit.MILLISECONDS))

            releaseReservation.countDown()
            val reservationMutation = reservationFuture.get(5, TimeUnit.SECONDS)
            val mutation = mutationFuture.get(5, TimeUnit.SECONDS)
            assertEquals(
                listOf(lowPriorityRule.id, highPriorityRule.id, missingBucketRule.id),
                reservationLockRuleIds,
            )
            assertEquals(
                listOf(highPriorityRule.id, lowPriorityRule.id, missingBucketRule.id),
                reservationMutation.events.map { it.limitRuleId },
            )
            assertEquals(
                listOf(highPriorityRule.id, lowPriorityRule.id, missingBucketRule.id),
                reservationMutation.buckets.map { it.limitRuleId },
            )
            assertEquals(2, mutation.events.size)
            assertEquals(
                listOf(lowPriorityRule.id, highPriorityRule.id),
                mutation.events.map { it.limitRuleId },
            )
            assertEquals(
                listOf(lowPriorityRule.id, highPriorityRule.id),
                mutation.buckets.map { it.limitRuleId },
            )

            assertEquals(
                2,
                usageEvents.findByTransactionEvent(
                    fixture.foundation.tenant.id,
                    mutationEvent.id,
                ).size,
            )
            listOf(lowPriorityRule to lowPriorityBucket, highPriorityRule to highPriorityBucket).forEach {
                (rule, bucket) ->
                val persisted = assertNotNull(
                    usageBuckets.findByLimitRuleAndAssigneeInWindow(
                        fixture.foundation.tenant.id,
                        rule.id,
                        fixture.foundation.ownerProfile.id,
                        EUR,
                        window,
                    ),
                )
                assertEquals(bucket.id, persisted.id)
                assertEquals(0, persisted.reservedAmount.amount.compareTo(BigDecimal("19")))
            }
            val persistedMissingBucket = assertNotNull(
                usageBuckets.findByLimitRuleAndAssigneeInWindow(
                    fixture.foundation.tenant.id,
                    missingBucketRule.id,
                    fixture.foundation.ownerProfile.id,
                    EUR,
                    window,
                ),
            )
            assertEquals(0, persistedMissingBucket.reservedAmount.amount.compareTo(BigDecimal("10")))
        } finally {
            releaseReservation.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `scheduled batch rolls back failed item before recording failure and continues`() {
        val foundation = seedFoundation()
        val currentRule = rules.insert(foundation.tenant.id, limitRule(foundation, ruleConfiguration(foundation)))
        val failedCandidate = currentRule.copy(
            amount = LimitAmount.Bounded(Money.positive("750", EUR)),
            version = currentRule.version.next(),
            updatedAt = NOW,
        )
        val currentRevision = revisions.insert(
            foundation.tenant.id,
            LimitRuleRevision(
                id = LimitRuleRevisionId.of(UUID.randomUUID()),
                tenantId = foundation.tenant.id,
                limitRuleId = currentRule.id,
                revisionNumber = RevisionNumber.of(1),
                revisionEventType = LimitRuleRevisionEventType.CREATED,
                actor = ChangeActor.System,
                afterAmount = currentRule.amount,
                effectiveAt = NOW,
                createdAt = NOW,
            ),
        )
        val conflictingRevision = revisions.insert(
            foundation.tenant.id,
            LimitRuleRevision(
                id = LimitRuleRevisionId.of(UUID.randomUUID()),
                tenantId = foundation.tenant.id,
                limitRuleId = currentRule.id,
                revisionNumber = RevisionNumber.of(2),
                revisionEventType = LimitRuleRevisionEventType.UPDATED,
                actor = ChangeActor.System,
                beforeAmount = currentRule.amount,
                afterAmount = failedCandidate.amount,
                effectiveAt = NOW,
                createdAt = NOW,
            ),
        )

        fun dueRequest(
            candidate: LimitRule,
            changeType: LimitRuleChangeType,
            current: LimitRule?,
            createdAt: Instant,
        ): LimitRuleChangeRequest {
            return LimitRuleChangeRequest(
                id = LimitChangeRequestId.of(UUID.randomUUID()),
                tenantId = foundation.tenant.id,
                changeType = changeType,
                requestedBy = ChangeActor.System,
                targetOwnerPartyProfileId = foundation.ownerProfile.id,
                configurationId = candidate.configurationId,
                requestedAmount = candidate.amount,
                status = LimitRuleChangeStatus.PENDING_EFFECTIVE_DATE,
                limitRuleId = current?.id,
                requestActorContext = RequestActorContext.System(),
                beforeAmount = current?.amount,
                effectiveAt = NOW,
                idempotencyKey = IdempotencyKey.of("due-${UUID.randomUUID()}"),
                createdAt = createdAt,
                updatedAt = createdAt,
            )
        }

        val failedRequest = changeRequests.insert(
            foundation.tenant.id,
            dueRequest(
                candidate = failedCandidate,
                changeType = LimitRuleChangeType.UPDATE,
                current = currentRule,
                createdAt = NOW.minusSeconds(2),
            ),
        )
        val unscopedConfiguration = ruleConfiguration(
            foundation, LimitChannelScope.AllChannels, LimitOperationScope.AllOperations,
        )
        val validCandidate = limitRule(foundation, unscopedConfiguration)
        val validRequest = changeRequests.insert(
            foundation.tenant.id,
            dueRequest(
                candidate = validCandidate,
                changeType = LimitRuleChangeType.CREATE,
                current = null,
                createdAt = NOW.minusSeconds(1),
            ),
        )

        val result = JooqLimitguardFactory(dataSource).create().limitChangeService.applyDueLimitChanges(
            ApplyDueLimitChangesCommand(
                tenantId = foundation.tenant.id.value,
                dueAt = NOW,
                limit = 10,
            ),
            trustedActorContext = TrustedChangeActorContext.InternalSystem,
        )

        assertEquals(1, result.failedCount)
        assertEquals(1, result.appliedCount)
        assertEquals(listOf(failedRequest.id.value), result.failedChangeRequestIds)
        assertEquals(listOf(validRequest.id.value), result.appliedChangeRequestIds)

        val failed = assertNotNull(changeRequests.findById(foundation.tenant.id, failedRequest.id))
        assertEquals(LimitRuleChangeStatus.FAILED, failed.status)
        assertTrue(failed.decisionReason.orEmpty().contains("insert limit rule revision conflicts with existing data"))
        assertEquals(
            listOf(LimitRuleChangeEventType.FAILED),
            changeEvents.findByChangeRequest(foundation.tenant.id, failedRequest.id).map { it.eventType },
        )
        assertEquals(currentRule, rules.findById(foundation.tenant.id, currentRule.id))
        assertEquals(
            conflictingRevision,
            revisions.findLatestByLimitRule(foundation.tenant.id, currentRule.id),
        )
        assertEquals(
            emptyList(),
            revisions.findByChangeRequest(foundation.tenant.id, failedRequest.id),
        )

        val applied = assertNotNull(changeRequests.findById(foundation.tenant.id, validRequest.id))
        assertEquals(LimitRuleChangeStatus.APPLIED, applied.status)
        val created = assertNotNull(rules.findById(foundation.tenant.id, assertNotNull(applied.limitRuleId)))
        assertEquals(unscopedConfiguration, created.configurationId)
        assertEquals(validCandidate.amount, created.amount)
        assertEquals(
            listOf(LimitRuleChangeEventType.APPLIED),
            changeEvents.findByChangeRequest(foundation.tenant.id, validRequest.id).map { it.eventType },
        )
        assertEquals(
            listOf(validRequest.id),
            revisions.findByChangeRequest(foundation.tenant.id, validRequest.id).map { it.changeRequestId },
        )
    }

    private fun seedFoundation(): FoundationFixture {
        val tenant = tenants.insert(
            Tenant(
                id = TenantId.of(UUID.randomUUID()),
                name = TenantName.of("Tenant ${UUID.randomUUID()}"),
                config = Metadata(mapOf("region" to "EU")),
                createdAt = NOW,
            )
        )
        val ownerParty = parties.insert(Party(PartyId.of(UUID.randomUUID()), PartyType.INDIVIDUAL, NOW))
        val ownerIndividual = individuals.insert(
            Individual(ownerParty.id, FirstName.of("Mario"), LastName.of("Rossi"), LocalDate.of(1985, 4, 12))
        )
        val ownerProfile = partyProfiles.insert(
            tenant.id,
            PartyProfile(
                id = PartyProfileId.of(UUID.randomUUID()),
                partyId = ownerParty.id,
                tenantId = tenant.id,
                createdAt = NOW,
                metadata = Metadata(mapOf("segment" to "retail")),
            ),
        )
        val delegateParty = parties.insert(Party(PartyId.of(UUID.randomUUID()), PartyType.INDIVIDUAL, NOW))
        individuals.insert(
            Individual(delegateParty.id, FirstName.of("Giulia"), LastName.of("Rossi"), LocalDate.of(2000, 6, 1))
        )
        val delegateProfile = partyProfiles.insert(
            tenant.id,
            PartyProfile(
                id = PartyProfileId.of(UUID.randomUUID()),
                partyId = delegateParty.id,
                tenantId = tenant.id,
                createdAt = NOW,
            ),
        )
        val delegation = delegations.insert(
            tenant.id,
            PartyProfileDelegation(
                id = PartyProfileDelegationId.of(UUID.randomUUID()),
                tenantId = tenant.id,
                ownerPartyProfileId = ownerProfile.id,
                delegatePartyProfileId = delegateProfile.id,
                delegationType = PartyProfileDelegationType.AUTHORIZED_USER,
                effectiveWindow = EffectiveWindow(NOW.minus(1, ChronoUnit.DAYS), null),
                metadata = Metadata(mapOf("relationship" to "family")),
                createdAt = NOW,
            ),
        )
        val channel = channels.insert(
            tenant.id,
            Channel(
                id = ChannelId.of(UUID.randomUUID()),
                tenantId = tenant.id,
                code = ChannelCode.of("MOBILE"),
                name = ChannelName.of("Mobile"),
                isActive = true,
                metadata = Metadata(mapOf("digital" to true)),
                createdAt = NOW,
            ),
        )
        val operation = operationTypes.insert(
            tenant.id,
            OperationType(
                id = OperationTypeId.of(UUID.randomUUID()),
                scope = OperationTypeScope.Global,
                parentId = null,
                code = OperationTypeCode.of("TRANSFER"),
                name = OperationTypeName.of("Transfer"),
                isLeaf = false,
                createdAt = NOW,
            ),
        )
        return FoundationFixture(
            tenant,
            ownerParty,
            ownerIndividual,
            ownerProfile,
            delegateProfile,
            delegation,
            channel,
            operation,
        )
    }

    private fun seedFunding(): FundingFixture {
        val foundation = seedFoundation()
        val rootProduct = products.insert(
            foundation.tenant.id,
            FundingSourceProduct(
                id = FundingSourceProductId.of(UUID.randomUUID()),
                tenantId = foundation.tenant.id,
                parentId = null,
                accountCategory = FundingSourceCategory.TRANSACTIONAL,
                nodeType = ProductNodeType.FAMILY,
                code = FundingSourceProductCode.of("CURRENT_ACCOUNTS"),
                name = FundingSourceProductName.of("Current accounts"),
                isLeaf = false,
                isActive = true,
                createdAt = NOW,
            ),
        )
        val leafProduct = products.insert(
            foundation.tenant.id,
            FundingSourceProduct(
                id = FundingSourceProductId.of(UUID.randomUUID()),
                tenantId = foundation.tenant.id,
                parentId = rootProduct.id,
                accountCategory = FundingSourceCategory.TRANSACTIONAL,
                nodeType = ProductNodeType.PRODUCT,
                code = FundingSourceProductCode.of("CC_STANDARD"),
                name = FundingSourceProductName.of("Standard account"),
                isLeaf = true,
                isActive = true,
                metadata = Metadata(mapOf("currency" to "EUR")),
                createdAt = NOW,
            ),
        )
        val fundingSource = fundingSources.insert(
            foundation.tenant.id,
            FundingSource(
                id = FundingSourceId.of(UUID.randomUUID()),
                tenantId = foundation.tenant.id,
                productId = leafProduct.id,
                externalReference = ExternalReference.of("ACCOUNT-${UUID.randomUUID()}"),
                isActive = true,
                metadata = Metadata(mapOf("country" to "IT")),
                createdAt = NOW,
            ),
        )
        val owner = fundingOwners.insert(
            foundation.tenant.id,
            FundingSourceOwner(
                id = FundingSourceOwnerId.of(UUID.randomUUID()),
                tenantId = foundation.tenant.id,
                partyProfileId = foundation.ownerProfile.id,
                fundingSourceId = fundingSource.id,
                createdAt = NOW,
            ),
        )
        val group = fundingGroups.insert(
            foundation.tenant.id,
            FundingSourceGroup(
                id = FundingSourceGroupId.of(UUID.randomUUID()),
                tenantId = foundation.tenant.id,
                ownerPartyProfileId = foundation.ownerProfile.id,
                code = FundingSourceGroupCode.of("HOUSEHOLD"),
                name = FundingSourceGroupName.of("Household"),
                metadata = Metadata(mapOf("shared" to true)),
                createdAt = NOW,
            ),
        )
        val member = fundingGroupMembers.insert(
            foundation.tenant.id,
            FundingSourceGroupMember(
                tenantId = foundation.tenant.id,
                groupId = group.id,
                ownerPartyProfileId = foundation.ownerProfile.id,
                fundingSourceId = fundingSource.id,
                createdAt = NOW,
            ),
        )
        return FundingFixture(foundation, rootProduct, leafProduct, fundingSource, owner, group, member)
    }

    /** A configuration that the default [limitRule] satisfies; rules cannot exist without one. */
    private fun ruleConfiguration(
        foundation: FoundationFixture,
        channelScope: LimitChannelScope = LimitChannelScope.ChannelSpecific(foundation.channel.id),
        operationScope: LimitOperationScope = LimitOperationScope.OperationTypeSpecific(foundation.operation.id),
        priority: Int = 10,
    ): LimitConfigurationId = configurations.insert(
        foundation.tenant.id,
        LimitConfiguration(
            id = LimitConfigurationId.of(UUID.randomUUID()),
            tenantId = foundation.tenant.id,
            parentConfigurationId = null,
            code = LimitConfigurationCode.of("RULE_${UUID.randomUUID()}"),
            name = LimitConfigurationName.of("Daily transfer"),
            description = null,
            limitKind = LimitKind.Periodic(LimitPeriod.DAILY),
            fundingScope = LimitConfigurationFundingScope.AllFundingSources,
            channelScope = channelScope,
            operationScope = operationScope,
            maxAssignableAmount = LimitAmount.Bounded(Money.positive("10000", EUR)),
            defaultAmount = null,
            priority = LimitPriority.of(priority),
            usageConsumptionMode = LimitUsageConsumptionMode.CASCADING,
            isActive = true,
            effectiveWindow = EffectiveWindow(NOW.minus(1, ChronoUnit.DAYS), null),
            createdAt = NOW,
        ),
    ).id

    private fun limitRule(
        foundation: FoundationFixture,
        configurationId: LimitConfigurationId,
    ): LimitRule = LimitRule(
        id = LimitRuleId.of(UUID.randomUUID()),
        tenantId = foundation.tenant.id,
        ownerPartyProfileId = foundation.ownerProfile.id,
        configurationId = configurationId,
        mandateScope = MandateScope.AllOwnerMandates,
        amount = LimitAmount.Bounded(Money.positive("500", EUR)),
        isActive = true,
        version = Version.INITIAL,
        metadata = Metadata(mapOf("source" to "integration")),
        createdAt = NOW,
        updatedAt = NOW,
    )

    private class BucketLockBarrierRepository(
        private val delegate: UsageBucketRepository,
        private val onFindForUpdate: ((LimitRuleId) -> Unit)? = null,
        private val beforeFirstLock: (() -> Unit)? = null,
        private val afterFirstLock: (() -> Unit)? = null,
    ) : UsageBucketRepository by delegate {
        private val beforeSignalled = AtomicBoolean(false)
        private val afterSignalled = AtomicBoolean(false)

        override fun findForUpdate(
            tenantId: TenantId,
            limitRuleId: LimitRuleId,
            assigneePartyProfileId: PartyProfileId,
            currency: CurrencyCode,
            window: UsageWindow,
        ): LimitUsageBucket? {
            onFindForUpdate?.invoke(limitRuleId)
            signalOnce(beforeFirstLock, beforeSignalled)
            val bucket = delegate.findForUpdate(
                tenantId,
                limitRuleId,
                assigneePartyProfileId,
                currency,
                window,
            )
            signalOnce(afterFirstLock, afterSignalled)
            return bucket
        }

        override fun findByIdForUpdate(
            tenantId: TenantId,
            id: UsageBucketId,
        ): LimitUsageBucket? {
            signalOnce(beforeFirstLock, beforeSignalled)
            val bucket = delegate.findByIdForUpdate(tenantId, id)
            signalOnce(afterFirstLock, afterSignalled)
            return bucket
        }

        private fun signalOnce(callback: (() -> Unit)?, signalled: AtomicBoolean) {
            if (callback != null && signalled.compareAndSet(false, true)) {
                callback()
            }
        }
    }

    private data class FoundationFixture(
        val tenant: Tenant,
        val ownerParty: Party,
        val ownerIndividual: Individual,
        val ownerProfile: PartyProfile,
        val delegateProfile: PartyProfile,
        val delegation: PartyProfileDelegation,
        val channel: Channel,
        val operation: OperationType,
    )

    private data class FundingFixture(
        val foundation: FoundationFixture,
        val rootProduct: FundingSourceProduct,
        val leafProduct: FundingSourceProduct,
        val fundingSource: FundingSource,
        val owner: FundingSourceOwner,
        val group: FundingSourceGroup,
        val groupMember: FundingSourceGroupMember,
    )

    companion object {
        private val NOW = Instant.parse("2026-08-09T00:00:00Z")
        private val EUR = CurrencyCode.of("EUR")

        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:18")

        @BeforeAll
        @JvmStatic
        fun initializeDatabase() {
            Flyway.configure()
                .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                .locations("classpath:db/limitguard/migration")
                .load()
                .migrate()
        }
    }
}
