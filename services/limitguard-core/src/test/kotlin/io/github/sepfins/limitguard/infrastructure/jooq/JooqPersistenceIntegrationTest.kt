package io.github.sepfins.limitguard.infrastructure.jooq

import io.github.sepfins.limitguard.domain.limit.LimitAmount
import io.github.sepfins.limitguard.api.errors.InfrastructureException
import io.github.sepfins.limitguard.domain.change.ChangeActor
import io.github.sepfins.limitguard.domain.change.LimitRuleChangeRequest
import io.github.sepfins.limitguard.domain.change.RequestActorContext
import io.github.sepfins.limitguard.domain.channel.Channel
import io.github.sepfins.limitguard.domain.channel.ChannelCode
import io.github.sepfins.limitguard.domain.channel.ChannelName
import io.github.sepfins.limitguard.domain.common.ChannelId
import io.github.sepfins.limitguard.domain.common.CurrencyCode
import io.github.sepfins.limitguard.domain.common.IdempotencyKey
import io.github.sepfins.limitguard.domain.common.LimitChangeRequestId
import io.github.sepfins.limitguard.domain.common.LimitConfigurationId
import io.github.sepfins.limitguard.domain.common.LimitRuleId
import io.github.sepfins.limitguard.domain.common.Metadata
import io.github.sepfins.limitguard.domain.common.Money
import io.github.sepfins.limitguard.domain.common.PartyProfileId
import io.github.sepfins.limitguard.domain.common.TenantId
import io.github.sepfins.limitguard.domain.common.UsageWindow
import io.github.sepfins.limitguard.domain.common.Version
import io.github.sepfins.limitguard.domain.limit.LimitRule
import io.github.sepfins.limitguard.domain.mandate.MandateScope
import io.github.sepfins.limitguard.infrastructure.jooq.repository.JooqChannelRepository
import io.github.sepfins.limitguard.infrastructure.jooq.repository.JooqLimitChangeRequestRepository
import io.github.sepfins.limitguard.infrastructure.jooq.repository.JooqLimitRuleRepository
import io.github.sepfins.limitguard.infrastructure.jooq.repository.JooqUsageBucketRepository
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
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Testcontainers
class JooqPersistenceIntegrationTest {
    private val dataSource: PGSimpleDataSource by lazy {
        PGSimpleDataSource().apply {
            setURL(postgres.jdbcUrl)
            user = postgres.username
            password = postgres.password
        }
    }
    private val context: JooqPersistenceContext by lazy { JooqPersistenceContext(dataSource) }
    private val transactions: JooqTransactionRunner by lazy { JooqTransactionRunner(context) }
    private val ruleRepository: JooqLimitRuleRepository by lazy { JooqLimitRuleRepository(context) }
    private val requestRepository: JooqLimitChangeRequestRepository by lazy {
        JooqLimitChangeRequestRepository(context)
    }
    private val bucketRepository: JooqUsageBucketRepository by lazy { JooqUsageBucketRepository(context) }

    @BeforeEach
    fun resetDatabase() {
        DSL.using(dataSource, SQLDialect.POSTGRES).execute("truncate table tenant, party cascade")
    }

    @Test
    fun `generated channel repository uses a consumer selected non public schema`() {
        val publicDsl = DSL.using(dataSource, SQLDialect.POSTGRES)
        val demoDataSource = PGSimpleDataSource().apply {
            setURL(postgres.jdbcUrl)
            currentSchema = DEMO_SCHEMA
            user = postgres.username
            password = postgres.password
        }

        publicDsl.execute("drop schema if exists $DEMO_SCHEMA cascade")
        try {
            Flyway.configure()
                .dataSource(demoDataSource)
                .defaultSchema(DEMO_SCHEMA)
                .schemas(DEMO_SCHEMA)
                .table("flyway_demo_schema_history")
                .locations("classpath:db/limitguard/migration")
                .load()
                .migrate()

            val demoDsl = DSL.using(demoDataSource, SQLDialect.POSTGRES)
            assertEquals(DEMO_SCHEMA, demoDsl.fetchOne("select current_schema()")?.get(0, String::class.java))

            val tenantId = TenantId.of(UUID.randomUUID())
            val channel = Channel(
                id = ChannelId.of(UUID.randomUUID()),
                tenantId = tenantId,
                code = ChannelCode.of("BRANCH"),
                name = ChannelName.of("Demo branch"),
                isActive = true,
                metadata = Metadata(mapOf("schema" to DEMO_SCHEMA)),
                createdAt = NOW,
            )
            demoDsl.execute(
                "insert into tenant (id, name, time_zone) values (?, ?, 'UTC')",
                tenantId.value,
                "Demo tenant ${tenantId.value}",
            )

            val demoContext = JooqPersistenceContext(demoDataSource)
            val repository = JooqChannelRepository(demoContext)
            assertEquals(channel, JooqTransactionRunner(demoContext).inTransaction {
                repository.insert(tenantId, channel)
            })
            assertEquals(channel, repository.findById(tenantId, channel.id))
            assertEquals(channel, repository.findByCode(tenantId, channel.code))

            assertEquals(1, countChannelRows(demoDsl, DEMO_SCHEMA, channel.id.value))
            assertEquals(0, countChannelRows(publicDsl, "public", channel.id.value))
        } finally {
            publicDsl.execute("drop schema if exists $DEMO_SCHEMA cascade")
        }
    }

    @Test
    fun `transaction runner shares one transaction and rolls it back`() {
        val fixture = seedOwner()
        val rule = rule(fixture)

        assertFailsWith<IllegalStateException> {
            transactions.inTransaction {
                transactions.inTransaction {
                    ruleRepository.insert(fixture.tenantId, rule)
                }
                error("force rollback")
            }
        }

        assertNull(ruleRepository.findById(fixture.tenantId, rule.id))
        assertFailsWith<InfrastructureException> {
            ruleRepository.findByIdForUpdate(fixture.tenantId, rule.id)
        }
    }

    @Test
    fun `logical bucket locks serialize concurrent first reservations`() {
        val fixture = seedOwner()
        val rule = ruleRepository.insert(fixture.tenantId, rule(fixture))
        val window = UsageWindow(NOW, NOW.plus(1, ChronoUnit.DAYS))
        val firstHasLock = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val secondReachedLock = CountDownLatch(1)
        val secondHasLock = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)

        try {
            val first = executor.submit {
                transactions.inTransaction {
                    bucketRepository.findForUpdate(fixture.tenantId, rule.id, fixture.ownerId, EUR, window)
                    firstHasLock.countDown()
                    check(releaseFirst.await(5, TimeUnit.SECONDS)) { "timed out waiting to release first lock" }
                }
            }
            assertTrue(firstHasLock.await(5, TimeUnit.SECONDS))

            val second = executor.submit {
                transactions.inTransaction {
                    secondReachedLock.countDown()
                    bucketRepository.findForUpdate(fixture.tenantId, rule.id, fixture.ownerId, EUR, window)
                    secondHasLock.countDown()
                }
            }
            assertTrue(secondReachedLock.await(5, TimeUnit.SECONDS))
            assertFalse(secondHasLock.await(250, TimeUnit.MILLISECONDS))

            releaseFirst.countDown()
            assertTrue(secondHasLock.await(5, TimeUnit.SECONDS))
            first.get(5, TimeUnit.SECONDS)
            second.get(5, TimeUnit.SECONDS)
        } finally {
            releaseFirst.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `due request claims skip rows held by another worker`() {
        val fixture = seedOwner()
        val inserted = requestRepository.insert(fixture.tenantId, changeRequest(fixture))
        val approved = requestRepository.update(
            fixture.tenantId,
            inserted.approve("approved", NOW),
        )
        val firstClaimed = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)

        try {
            val first = executor.submit {
                transactions.inTransaction {
                    check(requestRepository.claimForApplication(fixture.tenantId, approved.id, NOW) == approved)
                    firstClaimed.countDown()
                    check(releaseFirst.await(5, TimeUnit.SECONDS)) { "timed out waiting to release first claim" }
                }
            }
            assertTrue(firstClaimed.await(5, TimeUnit.SECONDS))

            val second = executor.submit<LimitRuleChangeRequest?> {
                transactions.inTransaction {
                    requestRepository.claimForApplication(fixture.tenantId, approved.id, NOW)
                }
            }
            assertNull(second.get(5, TimeUnit.SECONDS))

            releaseFirst.countDown()
            first.get(5, TimeUnit.SECONDS)
        } finally {
            releaseFirst.countDown()
            executor.shutdownNow()
        }
    }

    private fun seedOwner(): OwnerFixture {
        val fixture = OwnerFixture(
            tenantId = TenantId.of(UUID.randomUUID()),
            ownerId = PartyProfileId.of(UUID.randomUUID()),
            partyId = UUID.randomUUID(),
            configurationId = LimitConfigurationId.of(UUID.randomUUID()),
        )
        DSL.using(dataSource, SQLDialect.POSTGRES).apply {
            execute(
                "insert into tenant (id, name, time_zone) values (?, ?, 'UTC')",
                fixture.tenantId.value,
                "Tenant ${fixture.tenantId.value}",
            )
            execute("insert into party (id, party_type) values (?, 'INDIVIDUAL')", fixture.partyId)
            execute(
                "insert into party_profile (id, party_id, tenant_id) values (?, ?, ?)",
                fixture.ownerId.value,
                fixture.partyId,
                fixture.tenantId.value,
            )
            execute(
                "insert into limit_configuration (id, tenant_id, code, name, limit_kind, period, currency, max_assignable_amount) " +
                    "values (?, ?, 'DAILY_CASH', 'Daily cash', 'PERIODIC', 'DAILY', 'EUR', 10000)",
                fixture.configurationId.value,
                fixture.tenantId.value,
            )
        }
        return fixture
    }

    private fun rule(fixture: OwnerFixture): LimitRule = LimitRule(
        id = LimitRuleId.of(UUID.randomUUID()),
        tenantId = fixture.tenantId,
        ownerPartyProfileId = fixture.ownerId,
        configurationId = fixture.configurationId,
        mandateScope = MandateScope.AllOwnerMandates,
        amount = LimitAmount.Bounded(Money.positive("500.00", EUR)),
        isActive = true,
        version = Version.INITIAL,
        metadata = Metadata(mapOf("source" to "integration-test", "revision" to 1)),
    )

    private fun changeRequest(fixture: OwnerFixture): LimitRuleChangeRequest = LimitRuleChangeRequest(
        id = LimitChangeRequestId.of(UUID.randomUUID()),
        tenantId = fixture.tenantId,
        changeType = io.github.sepfins.limitguard.domain.change.LimitRuleChangeType.CREATE,
        requestedBy = ChangeActor.System,
        targetOwnerPartyProfileId = fixture.ownerId,
        configurationId = fixture.configurationId,
        requestedAmount = LimitAmount.Bounded(Money.positive("500.00", EUR)),
        requestActorContext = RequestActorContext.System(),
        policyContext = Metadata(mapOf("source" to "integration-test")),
        idempotencyKey = IdempotencyKey.of("change-${UUID.randomUUID()}"),
        metadata = Metadata(mapOf("labels" to listOf("daily", "cash"))),
    )

    private fun countChannelRows(dsl: org.jooq.DSLContext, schema: String, id: UUID): Int =
        dsl.selectCount()
            .from(DSL.table(DSL.name(schema, "channel")))
            .where(DSL.field(DSL.name("id"), UUID::class.java).eq(id))
            .fetchOne(0, Int::class.java) ?: 0

    private data class OwnerFixture(
        val tenantId: TenantId,
        val ownerId: PartyProfileId,
        val partyId: UUID,
        val configurationId: LimitConfigurationId,
    )

    companion object {
        private val NOW: Instant = Instant.parse("2026-08-09T00:00:00Z")
        private val EUR: CurrencyCode = CurrencyCode.of("EUR")
        private const val DEMO_SCHEMA = "limitguard_demo"

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
