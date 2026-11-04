package io.github.sepfins.limitguard.infrastructure.jooq

import io.github.sepfins.limitguard.api.LimitAmount
import io.github.sepfins.limitguard.api.TrustedChangeActorContext
import io.github.sepfins.limitguard.api.FundingSourceCategory
import io.github.sepfins.limitguard.api.LimitChangeActorType
import io.github.sepfins.limitguard.api.LimitConfigurationFundingScope
import io.github.sepfins.limitguard.api.LimitKind
import io.github.sepfins.limitguard.api.LimitRuleChangeStatus
import io.github.sepfins.limitguard.api.LimitRuleChangeType
import io.github.sepfins.limitguard.api.Limitguard
import io.github.sepfins.limitguard.api.PartyType
import io.github.sepfins.limitguard.api.commands.ApplyDueLimitChangesCommand
import io.github.sepfins.limitguard.api.commands.ApproveLimitRuleChangeCommand
import io.github.sepfins.limitguard.api.commands.CancelLimitRuleChangeCommand
import io.github.sepfins.limitguard.api.commands.CreateLimitConfigurationCommand
import io.github.sepfins.limitguard.api.commands.LimitChangeActorCommand
import io.github.sepfins.limitguard.api.commands.LimitRuleChangeOperation
import io.github.sepfins.limitguard.api.commands.LimitRuleTargetCommand
import io.github.sepfins.limitguard.api.commands.RegisterPartyProfileCommand
import io.github.sepfins.limitguard.api.commands.RegisterTenantCommand
import io.github.sepfins.limitguard.api.commands.RejectLimitRuleChangeCommand
import io.github.sepfins.limitguard.api.commands.RequestLimitRuleChangeCommand
import io.github.sepfins.limitguard.api.errors.ConflictException
import io.github.sepfins.limitguard.api.queries.GetLimitChangeQuery
import io.github.sepfins.limitguard.api.results.LimitChangeRequestResult
import io.github.sepfins.limitguard.support.FixedClockProvider
import io.github.sepfins.limitguard.support.persistence.ObservedDataSource
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

@Testcontainers
@Timeout(30)
class ChangeWorkflowLockingIntegrationTest {
    private val dataSource by lazy {
        PGSimpleDataSource().apply {
            setURL(postgres.jdbcUrl)
            user = postgres.username
            password = postgres.password
            options = "-c lock_timeout=5000 -c statement_timeout=10000 -c deadlock_timeout=100"
        }
    }
    private val clock = FixedClockProvider(NOW)
    private val facade by lazy { JooqLimitguardFactory(dataSource).create(clockProvider = clock) }

    @Test
    fun `a submission for a target the due batch is applying conflicts and a retry finds the rule the batch created`() {
        val fixture = fixture()
        val savings = configuration(fixture.tenant, "SAVINGS", FundingSourceCategory.SAVINGS)
        val scheduledCreate = facade.limitChangeService.requestLimitRuleChange(
            fixture.create(savings).copy(effectiveAt = DUE),
            trustedActorContext = TrustedChangeActorContext.InternalSystem,
        )
        facade.limitChangeService.approveLimitRuleChange(ApproveLimitRuleChangeCommand(
            fixture.tenant, scheduledCreate.id, SYSTEM,
        ),
            trustedActorContext = TrustedChangeActorContext.InternalSystem,
        )
        val update = fixture.create(savings).copy(expectedVersion = 1, idempotencyKey = "savings-update")

        val firstRevisionWritten = CountDownLatch(1)
        val continueBatch = CountDownLatch(1)
        val pauseOnce = AtomicBoolean()
        val dueFacade = facade(ObservedDataSource(dataSource, after = { sql ->
            if (sql.startsWith("insert into \"limit_rule_revision\"") && pauseOnce.compareAndSet(false, true)) {
                firstRevisionWritten.countDown()
                await(continueBatch)
            }
        }))
        val executor = Executors.newFixedThreadPool(1)
        try {
            val application = executor.submit<io.github.sepfins.limitguard.api.results.ApplyDueChangesResult> {
                dueFacade.limitChangeService.applyDueLimitChanges(ApplyDueLimitChangesCommand(fixture.tenant, DUE), trustedActorContext = TrustedChangeActorContext.InternalSystem)
            }
            await(firstRevisionWritten)
            assertFailsWith<ConflictException> {
                facade.limitChangeService.requestLimitRuleChange(update, trustedActorContext = TrustedChangeActorContext.InternalSystem)
            }
            continueBatch.countDown()

            assertEquals(listOf(scheduledCreate.id), application.get(10, TimeUnit.SECONDS).appliedChangeRequestIds)
            val details = facade.limitInquiryService.getLimitChange(GetLimitChangeQuery(fixture.tenant, scheduledCreate.id))
            assertEquals(LimitRuleChangeStatus.APPLIED, details.request.status)
            assertEquals(1, details.revisions.size)
            val retried = facade.limitChangeService.requestLimitRuleChange(update, trustedActorContext = TrustedChangeActorContext.InternalSystem)
            assertEquals(LimitRuleChangeType.UPDATE, retried.changeType)
            assertEquals(details.request.limitRuleId, retried.limitRuleId)
        } finally {
            continueBatch.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `concurrent requests for one target admit one complete workflow and leave the losing key reusable`() {
        val fixture = fixture()
        val savings = configuration(fixture.tenant, "SAVINGS", FundingSourceCategory.SAVINGS)
        val credit = configuration(fixture.tenant, "CREDIT", FundingSourceCategory.CREDIT)
        val firstCommand = fixture.create(savings).copy(idempotencyKey = "first")
        val secondCommand = fixture.create(savings).copy(idempotencyKey = "second")
        val barrier = WorkflowWriteBarrier(dataSource)
        val firstFacade = facade(barrier.first)
        val secondFacade = facade(barrier.second)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val first = executor.submit<io.github.sepfins.limitguard.api.results.LimitChangeRequestResult> {
                firstFacade.limitChangeService.requestLimitRuleChange(firstCommand, trustedActorContext = TrustedChangeActorContext.InternalSystem)
            }
            await(barrier.inserted)
            val second = executor.submit<Result<io.github.sepfins.limitguard.api.results.LimitChangeRequestResult>> {
                runCatching { secondFacade.limitChangeService.requestLimitRuleChange(secondCommand, trustedActorContext = TrustedChangeActorContext.InternalSystem) }
            }
            await(barrier.contenderAtTarget)
            barrier.release.countDown()
            val winner = first.get(10, TimeUnit.SECONDS)
            assertIs<ConflictException>(second.get(10, TimeUnit.SECONDS).exceptionOrNull())
            assertEquals(winner, facade.limitChangeService.requestLimitRuleChange(firstCommand, trustedActorContext = TrustedChangeActorContext.InternalSystem))
            val details = facade.limitInquiryService.getLimitChange(GetLimitChangeQuery(fixture.tenant, winner.id))
            assertEquals(listOf("REQUESTED"), details.events.map { it.eventType.name })
            assertEquals(emptyList(), details.revisions)

            val recovered = facade.limitChangeService.requestLimitRuleChange(secondCommand.copy(
                target = secondCommand.target.copy(configurationId = credit),
            ),
                trustedActorContext = TrustedChangeActorContext.InternalSystem,
            )
            assertEquals(LimitRuleChangeStatus.REQUESTED, recovered.status)
            assertEquals(listOf("REQUESTED"), facade.limitInquiryService
                .getLimitChange(GetLimitChangeQuery(fixture.tenant, recovered.id)).events.map { it.eventType.name })
        } finally {
            barrier.release.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `concurrent identical creation keys replay one workflow after waiting on the pending target index`() {
        val fixture = fixture()
        val savings = configuration(fixture.tenant, "SAVINGS", FundingSourceCategory.SAVINGS)
        val command = fixture.create(savings).copy(idempotencyKey = "same-intent")
        val barrier = WorkflowWriteBarrier(dataSource)
        val firstFacade = facade(barrier.first)
        val secondFacade = facade(barrier.second)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val first = executor.submit<io.github.sepfins.limitguard.api.results.LimitChangeRequestResult> {
                firstFacade.limitChangeService.requestLimitRuleChange(command, trustedActorContext = TrustedChangeActorContext.InternalSystem)
            }
            await(barrier.inserted)
            val second = executor.submit<io.github.sepfins.limitguard.api.results.LimitChangeRequestResult> {
                secondFacade.limitChangeService.requestLimitRuleChange(command, trustedActorContext = TrustedChangeActorContext.InternalSystem)
            }
            await(barrier.contenderAtTarget)
            barrier.release.countDown()
            val winner = first.get(10, TimeUnit.SECONDS)
            assertEquals(winner, second.get(10, TimeUnit.SECONDS))
            assertEquals(listOf("REQUESTED"), facade.limitInquiryService
                .getLimitChange(GetLimitChangeQuery(fixture.tenant, winner.id)).events.map { it.eventType.name })
        } finally {
            barrier.release.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `competing existing request decisions commit one outcome and at most one revision`() {
        val fixture = fixture()
        val savings = configuration(fixture.tenant, "SAVINGS", FundingSourceCategory.SAVINGS)
        val command = fixture.create(savings).copy(idempotencyKey = "original")
        val pending = facade.limitChangeService.requestLimitRuleChange(command, trustedActorContext = TrustedChangeActorContext.InternalSystem)
        val approvalFacade = facade(dataSource)
        val rejectionFacade = facade(dataSource)
        val start = CyclicBarrier(2)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val approval = executor.submit<Result<io.github.sepfins.limitguard.api.results.LimitChangeRequestResult>> {
                start.await(10, TimeUnit.SECONDS)
                runCatching { approvalFacade.limitChangeService.approveLimitRuleChange(
                    ApproveLimitRuleChangeCommand(fixture.tenant, pending.id, SYSTEM),
                    trustedActorContext = TrustedChangeActorContext.InternalSystem,
                ) }
            }
            val rejection = executor.submit<Result<io.github.sepfins.limitguard.api.results.LimitChangeRequestResult>> {
                start.await(10, TimeUnit.SECONDS)
                runCatching { rejectionFacade.limitChangeService.rejectLimitRuleChange(
                    RejectLimitRuleChangeCommand(fixture.tenant, pending.id, SYSTEM, "Declined"),
                    trustedActorContext = TrustedChangeActorContext.InternalSystem,
                ) }
            }
            val results = listOf(approval.get(10, TimeUnit.SECONDS), rejection.get(10, TimeUnit.SECONDS))
            assertEquals(1, results.count { it.isSuccess })
            assertIs<ConflictException>(results.single { it.isFailure }.exceptionOrNull())
            val winner = results.single { it.isSuccess }.getOrThrow()
            val details = facade.limitInquiryService.getLimitChange(GetLimitChangeQuery(fixture.tenant, pending.id))
            assertEquals(winner, details.request)
            val revisionCount = if (winner.status == LimitRuleChangeStatus.APPLIED) 1 else 0
            assertEquals(revisionCount, details.revisions.size)
            assertEquals(revisionCount, details.events.count { it.eventType.name == "APPLIED" })
            assertEquals(winner, facade.limitChangeService.requestLimitRuleChange(command, trustedActorContext = TrustedChangeActorContext.InternalSystem))
        } finally {
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `distinct funded targets of one owner can be admitted without waiting for each other`() {
        val fixture = fixture()
        val savings = configuration(fixture.tenant, "SAVINGS", FundingSourceCategory.SAVINGS)
        val credit = configuration(fixture.tenant, "CREDIT", FundingSourceCategory.CREDIT)
        val barrier = WorkflowWriteBarrier(dataSource)
        val firstFacade = facade(barrier.first)
        val secondFacade = facade(barrier.second)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val first = executor.submit<io.github.sepfins.limitguard.api.results.LimitChangeRequestResult> {
                firstFacade.limitChangeService.requestLimitRuleChange(fixture.create(savings), trustedActorContext = TrustedChangeActorContext.InternalSystem)
            }
            await(barrier.inserted)
            val second = executor.submit<io.github.sepfins.limitguard.api.results.LimitChangeRequestResult> {
                secondFacade.limitChangeService.requestLimitRuleChange(fixture.create(credit), trustedActorContext = TrustedChangeActorContext.InternalSystem)
            }
            assertEquals(LimitRuleChangeStatus.REQUESTED, second.get(5, TimeUnit.SECONDS).status)
            barrier.release.countDown()
            assertEquals(LimitRuleChangeStatus.REQUESTED, first.get(10, TimeUnit.SECONDS).status)
        } finally {
            barrier.release.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `another due worker skips a busy target and applies unrelated scheduled work`() {
        val fixture = fixture()
        val savings = configuration(fixture.tenant, "SAVINGS", FundingSourceCategory.SAVINGS)
        val credit = configuration(fixture.tenant, "CREDIT", FundingSourceCategory.CREDIT)
        val firstRequest = facade.limitChangeService.requestLimitRuleChange(
            fixture.create(savings).copy(effectiveAt = DUE),
            trustedActorContext = TrustedChangeActorContext.InternalSystem,
        )
        facade.limitChangeService.approveLimitRuleChange(ApproveLimitRuleChangeCommand(fixture.tenant, firstRequest.id, SYSTEM), trustedActorContext = TrustedChangeActorContext.InternalSystem)
        clock.advanceBy(Duration.ofSeconds(1))
        val secondRequest = facade.limitChangeService.requestLimitRuleChange(
            fixture.create(credit).copy(effectiveAt = DUE),
            trustedActorContext = TrustedChangeActorContext.InternalSystem,
        )
        facade.limitChangeService.approveLimitRuleChange(ApproveLimitRuleChangeCommand(fixture.tenant, secondRequest.id, SYSTEM), trustedActorContext = TrustedChangeActorContext.InternalSystem)
        val barrier = WorkflowWriteBarrier(dataSource, "limit_rule_revision")
        val firstFacade = facade(barrier.first)
        val secondFacade = facade(dataSource)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val first = executor.submit<io.github.sepfins.limitguard.api.results.ApplyDueChangesResult> {
                firstFacade.limitChangeService.applyDueLimitChanges(ApplyDueLimitChangesCommand(fixture.tenant, DUE, 1), trustedActorContext = TrustedChangeActorContext.InternalSystem)
            }
            await(barrier.inserted)
            val second = executor.submit<io.github.sepfins.limitguard.api.results.ApplyDueChangesResult> {
                secondFacade.limitChangeService.applyDueLimitChanges(ApplyDueLimitChangesCommand(fixture.tenant, DUE), trustedActorContext = TrustedChangeActorContext.InternalSystem)
            }
            val unrelated = second.get(5, TimeUnit.SECONDS)
            assertEquals(listOf(secondRequest.id), unrelated.appliedChangeRequestIds)
            assertEquals(0, unrelated.failedCount)
            barrier.release.countDown()
            assertEquals(listOf(firstRequest.id), first.get(10, TimeUnit.SECONDS).appliedChangeRequestIds)
            for (id in listOf(firstRequest.id, secondRequest.id)) {
                assertEquals(1, facade.limitInquiryService.getLimitChange(GetLimitChangeQuery(fixture.tenant, id)).revisions.size)
            }
        } finally {
            barrier.release.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `cancellation competing with due application returns a lifecycle conflict without a second revision`() {
        val fixture = fixture()
        val savings = configuration(fixture.tenant, "SAVINGS", FundingSourceCategory.SAVINGS)
        val request = facade.limitChangeService.requestLimitRuleChange(
            fixture.create(savings).copy(effectiveAt = DUE),
            trustedActorContext = TrustedChangeActorContext.InternalSystem,
        )
        facade.limitChangeService.approveLimitRuleChange(ApproveLimitRuleChangeCommand(fixture.tenant, request.id, SYSTEM), trustedActorContext = TrustedChangeActorContext.InternalSystem)
        val barrier = WorkflowWriteBarrier(dataSource, "limit_rule_revision") {
            it.contains("for update of \"limit_rule_change_request\"")
        }
        val applyingFacade = facade(barrier.first)
        val cancellingFacade = facade(barrier.second)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val application = executor.submit<io.github.sepfins.limitguard.api.results.ApplyDueChangesResult> {
                applyingFacade.limitChangeService.applyDueLimitChanges(ApplyDueLimitChangesCommand(fixture.tenant, DUE), trustedActorContext = TrustedChangeActorContext.InternalSystem)
            }
            await(barrier.inserted)
            val cancellation = executor.submit<Result<io.github.sepfins.limitguard.api.results.LimitChangeRequestResult>> {
                runCatching { cancellingFacade.limitChangeService.cancelLimitRuleChange(
                    CancelLimitRuleChangeCommand(fixture.tenant, request.id, SYSTEM),
                    trustedActorContext = TrustedChangeActorContext.InternalSystem,
                ) }
            }
            await(barrier.contenderAtTarget)
            barrier.release.countDown()
            assertEquals(listOf(request.id), application.get(10, TimeUnit.SECONDS).appliedChangeRequestIds)
            assertIs<ConflictException>(cancellation.get(10, TimeUnit.SECONDS).exceptionOrNull())
            val details = facade.limitInquiryService.getLimitChange(GetLimitChangeQuery(fixture.tenant, request.id))
            assertEquals(LimitRuleChangeStatus.APPLIED, details.request.status)
            assertEquals(1, details.revisions.size)
            assertEquals(0, details.events.count { it.eventType.name == "CANCELLED" })
        } finally {
            barrier.release.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    private fun facade(source: DataSource): Limitguard =
        JooqLimitguardFactory(source).create(clockProvider = FixedClockProvider(NOW))

    private fun fixture(): Fixture {
        val tenant = facade.tenantCatalogService.registerTenant(RegisterTenantCommand("Locking bank ${UUID.randomUUID()}", ZoneOffset.UTC)).id
        val owner = facade.tenantCatalogService.registerPartyProfile(RegisterPartyProfileCommand(
            tenantId = tenant, partyType = PartyType.INDIVIDUAL, firstName = "Alex", lastName = "Customer",
        )).id
        return Fixture(tenant, owner)
    }

    private fun configuration(tenant: UUID, code: String, category: FundingSourceCategory): UUID =
        facade.tenantCatalogService.createLimitConfiguration(CreateLimitConfigurationCommand(
            tenantId = tenant, code = code, name = code, limitKind = LimitKind.PER_TRANSACTION,
            currency = "EUR", maxAssignableAmount = LimitAmount.Bounded(BigDecimal("1000")),
            fundingScope = LimitConfigurationFundingScope.FUNDING_SOURCE_CATEGORY, fundingSourceCategory = category,
        )).id

    private data class Fixture(val tenant: UUID, val owner: UUID) {
        fun create(configuration: UUID) = RequestLimitRuleChangeCommand(
            tenantId = tenant, requestedBy = SYSTEM,
            target = LimitRuleTargetCommand(owner, configuration),
            operation = LimitRuleChangeOperation.SetAmount(LimitAmount.Bounded(BigDecimal("500"))),
        )
    }

    private class WorkflowWriteBarrier(
        source: DataSource,
        table: String = "limit_rule_change_request",
        contender: (String) -> Boolean = { it.startsWith("insert into \"limit_rule_change_request\"") },
    ) {
        val inserted = CountDownLatch(1)
        val release = CountDownLatch(1)
        val contenderAtTarget = CountDownLatch(1)
        private val pauseOnce = AtomicBoolean()
        val first: DataSource = ObservedDataSource(source, after = { sql ->
            if (sql.startsWith("insert into \"$table\"") && pauseOnce.compareAndSet(false, true)) {
                inserted.countDown()
                await(release)
            }
        })
        val second: DataSource = ObservedDataSource(source, before = { sql ->
            if (contender(sql)) contenderAtTarget.countDown()
        })
    }

    companion object {
        private val NOW = Instant.parse("2026-10-02T10:00:00Z")
        private val DUE = NOW.plusSeconds(60)
        private val SYSTEM = LimitChangeActorCommand(LimitChangeActorType.SYSTEM)

        private fun await(latch: CountDownLatch) {
            assertTrue(latch.await(10, TimeUnit.SECONDS), "contention barrier timed out")
        }

        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:18")

        @BeforeAll
        @JvmStatic
        fun initializeDatabase() {
            Flyway.configure().dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                .locations("classpath:db/limitguard/migration").load().migrate()
        }
    }
}
