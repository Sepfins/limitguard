package io.github.sepfins.limitguard.infrastructure.jooq

import io.github.sepfins.limitguard.api.FundingSourceCategory
import io.github.sepfins.limitguard.api.Limitguard
import io.github.sepfins.limitguard.api.LimitConfigurationFundingScope
import io.github.sepfins.limitguard.api.AuthorizationDecision
import io.github.sepfins.limitguard.api.LimitRuleChangeStatus
import io.github.sepfins.limitguard.api.ProductNodeType
import io.github.sepfins.limitguard.api.commands.LimitRuleChangeOperation
import io.github.sepfins.limitguard.api.commands.RegisterFundingSourceProductCommand
import io.github.sepfins.limitguard.api.commands.SetLimitConfigurationActiveCommand
import io.github.sepfins.limitguard.api.commands.UpdateLimitConfigurationCommand
import io.github.sepfins.limitguard.api.errors.InfrastructureException
import io.github.sepfins.limitguard.api.errors.ValidationException
import io.github.sepfins.limitguard.support.FixedClockProvider
import io.github.sepfins.limitguard.support.FundingEnvelopeScenario
import io.github.sepfins.limitguard.support.persistence.ObservedDataSource
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import java.time.Instant
import java.sql.SQLException
import java.sql.Connection
import java.math.BigDecimal
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Testcontainers
@Timeout(30)
class FundingEnvelopePersistenceTest {
    @Test
    fun `unsafe transaction snapshots fail before funding envelope admission growth or maintenance writes`() {
        val fixture = fixture()
        val s = fixture.scenario
        val family = s.product("FAMILY")
        val parent = s.configuration("PARENT")
        val repeatableRead = object : DataSource by fixture.source {
            override fun getConnection(): Connection = fixture.source.connection.apply {
                transactionIsolation = Connection.TRANSACTION_REPEATABLE_READ
            }
        }
        val unsafe = JooqLimitguardFactory(repeatableRead).create(clockProvider = FixedClockProvider(NOW))

        for (operation in listOf("configuration", "product", "description", "activation")) {
            val failure = assertFailsWith<InfrastructureException> {
                when (operation) {
                    "configuration" -> unsafe.tenantCatalogService.createLimitConfiguration(s.configurationCommand("NEW", parent.id, product = family.id))
                    "product" -> unsafe.tenantCatalogService.registerFundingSourceProduct(RegisterFundingSourceProductCommand(
                        s.tenant, "NEW", "New", FundingSourceCategory.TRANSACTIONAL, ProductNodeType.PRODUCT,
                        parentId = family.id, isLeaf = true,
                    ))
                    "description" -> unsafe.tenantCatalogService.updateLimitConfiguration(UpdateLimitConfigurationCommand(
                        s.tenant, parent.id, "Changed", null, BigDecimal.ONE, emptyMap(),
                    ))
                    else -> unsafe.tenantCatalogService.setLimitConfigurationActive(SetLimitConfigurationActiveCommand(s.tenant, parent.id, false))
                }
            }
            assertTrue(failure.message.orEmpty().contains("READ COMMITTED"))
            assertEquals(parent, s.findConfiguration("PARENT"))
            assertEquals(family, s.findProduct("FAMILY"))
            assertNull(s.findConfiguration("NEW"))
            assertNull(s.findProduct("NEW"))
        }
        s.configuration("NEW", parent.id, product = family.id)
        s.product("NEW", family.id, leaf = true)
    }

    @Test
    fun `configuration admission commits before contending growth revalidates disabled envelopes`() = contend(configurationFirst = true)

    @Test
    fun `growth commits before contending configuration admission reads current leaf coverage`() = contend(configurationFirst = false)

    @Test
    fun `empty category admission commits before unrelated root growth revalidates its product parent`() =
        contend(configurationFirst = true, categoryChild = true)

    @Test
    fun `root product growth commits before category admission revalidates its product parent`() =
        contend(configurationFirst = false, categoryChild = true)

    @Test
    fun `invalid existing disabled ancestors are reported without partial catalog or configuration writes`() {
        val fixture = fixture()
        val s = fixture.scenario
        val family = s.product("FAMILY")
        s.product("SAVINGS", family.id, FundingSourceCategory.SAVINGS, leaf = true, active = false)
        val root = s.configuration("ROOT", active = false)
        val parent = s.configuration("PARENT", root.id, product = family.id, active = false)
        execute(fixture.source, "update limit_configuration set funding_scope = 'FUNDING_SOURCE_CATEGORY', funding_source_category = 'TRANSACTIONAL' where id = ?", root.id)
        val invalidRoot = s.findConfiguration("ROOT")

        val failure = assertFailsWith<ValidationException> { s.configuration("CHILD", parent.id, product = family.id) }
        assertTrue(failure.message.orEmpty().contains("correct the funding envelope explicitly"))
        assertFailsWith<ValidationException> { s.product("NEW_ACCOUNT", family.id, leaf = true) }
        assertFailsWith<ValidationException> {
            s.facade.tenantCatalogService.updateLimitConfiguration(UpdateLimitConfigurationCommand(
                s.tenant, parent.id, "Changed", null, BigDecimal.ONE, emptyMap(),
            ))
        }
        assertFailsWith<ValidationException> {
            s.facade.tenantCatalogService.setLimitConfigurationActive(SetLimitConfigurationActiveCommand(s.tenant, parent.id, true))
        }
        assertNull(s.findConfiguration("CHILD"))
        assertNull(s.findProduct("NEW_ACCOUNT"))
        assertEquals(parent, s.findConfiguration("PARENT"))
        assertEquals(invalidRoot, s.findConfiguration("ROOT"))

        execute(fixture.source, "update limit_configuration set funding_scope = 'ALL_FUNDING_SOURCES', funding_source_category = null where id = ?", root.id)
        assertEquals(root, s.findConfiguration("ROOT"))
        assertEquals(parent.id, s.configuration("CHILD", parent.id, product = family.id).parentConfigurationId)
        assertEquals(family.id, s.product("NEW_ACCOUNT", family.id, leaf = true).parentId)
    }

    @Test
    fun `post write infrastructure failures roll back admission growth and descriptive updates and release their locks`() {
        for (operation in listOf("product", "configuration", "description", "activation")) {
            val fixture = fixture()
            val s = fixture.scenario
            val family = s.product("FAMILY")
            val parent = s.configuration("PARENT")
            val table = if (operation == "product") "funding_source_product" else "limit_configuration"
            val verb = if (operation in listOf("description", "activation")) "update" else "insert into"
            val failing = JooqLimitguardFactory(ObservedDataSource(fixture.source, after = { sql ->
                if (sql.startsWith("$verb \"$table\"")) throw SQLException("Injected failure after envelope write", "XX000")
            })).create(clockProvider = FixedClockProvider(NOW))

            assertFailsWith<InfrastructureException> {
                when (operation) {
                    "product" -> failing.tenantCatalogService.registerFundingSourceProduct(RegisterFundingSourceProductCommand(
                        s.tenant, "NEW", "New", FundingSourceCategory.TRANSACTIONAL, ProductNodeType.PRODUCT,
                        parentId = family.id, isLeaf = true,
                    ))
                    "configuration" -> failing.tenantCatalogService.createLimitConfiguration(s.configurationCommand("NEW", parent.id, product = family.id))
                    "description" -> failing.tenantCatalogService.updateLimitConfiguration(UpdateLimitConfigurationCommand(
                        s.tenant, parent.id, "Changed", "Changed", BigDecimal.ONE, mapOf("changed" to true),
                    ))
                    else -> failing.tenantCatalogService.setLimitConfigurationActive(SetLimitConfigurationActiveCommand(s.tenant, parent.id, false))
                }
            }
            assertEquals(parent, s.findConfiguration("PARENT"))
            assertEquals(family, s.findProduct("FAMILY"))
            assertNull(s.findConfiguration("NEW"))
            assertNull(s.findProduct("NEW"))
            s.product("NEW", family.id, leaf = true)
            s.configuration("NEW", parent.id, product = family.id)
        }
    }

    @Test
    fun `disabled PostgreSQL envelopes preserve granted evaluation and allow deactivation and retirement`() {
        val s = fixture().scenario
        val family = s.product("FAMILY")
        val source = s.source(s.product("ACCOUNT", family.id, leaf = true).id)
        val root = s.configuration("ROOT")
        val child = s.configuration("CHILD", root.id, product = family.id)
        val rule = s.grant(child)
        val command = s.requestCommand(child)
        s.facade.tenantCatalogService.setLimitConfigurationActive(SetLimitConfigurationActiveCommand(s.tenant, root.id, false))

        assertFailsWith<ValidationException> { s.facade.limitChangeService.requestLimitRuleChange(command) }
        val allowed = s.authorize(source, "40")
        assertEquals(AuthorizationDecision.ALLOWED, allowed.decision)
        assertEquals(listOf(rule.id), allowed.matchedRules.map { it.id })
        assertEquals(AuthorizationDecision.DENIED, s.authorize(source, "501").decision)
        val deactivate = s.facade.limitChangeService.requestLimitRuleChange(command.copy(
            operation = LimitRuleChangeOperation.Deactivate, expectedVersion = 1,
        ))
        assertEquals(LimitRuleChangeStatus.APPLIED, s.approve(deactivate.id).status)
        assertFailsWith<ValidationException> {
            s.facade.limitChangeService.requestLimitRuleChange(command.copy(
                operation = LimitRuleChangeOperation.Reactivate, expectedVersion = 2,
            ))
        }
        val retire = s.facade.limitChangeService.requestLimitRuleChange(command.copy(
            operation = LimitRuleChangeOperation.Delete, expectedVersion = 2,
        ))
        assertEquals(LimitRuleChangeStatus.APPLIED, s.approve(retire.id).status)
        assertEquals(3, s.rule(rule.id).version)
        assertEquals(false, s.rule(rule.id).isActive)
    }

    private fun contend(configurationFirst: Boolean, categoryChild: Boolean = false) {
        val fixture = fixture()
        val s = fixture.scenario
        val family = s.product("FAMILY")
        val parent = if (categoryChild) s.configuration("PARENT", product = family.id, active = false)
            else s.configuration("PARENT", category = FundingSourceCategory.TRANSACTIONAL, active = false)
        val configuration = if (categoryChild) s.configurationCommand("CHILD", parent.id, category = FundingSourceCategory.SAVINGS, active = false)
            else s.configurationCommand("CHILD", parent.id, product = family.id, active = false)
        val growth = RegisterFundingSourceProductCommand(
            s.tenant, "OUTSIDE", "Outside", FundingSourceCategory.SAVINGS, ProductNodeType.PRODUCT,
            parentId = if (categoryChild) null else family.id, isLeaf = true, isActive = false,
        )
        val snapshotRead = CountDownLatch(1)
        val release = CountDownLatch(1)
        val contenderRead = CountDownLatch(1)
        val contenderDone = CountDownLatch(1)
        val observed = AtomicBoolean()
        val snapshotTable = if (configurationFirst) "funding_source_product" else "limit_configuration"
        val holder = JooqLimitguardFactory(ObservedDataSource(fixture.source, after = { sql ->
            if (isSnapshot(sql, snapshotTable) && observed.compareAndSet(false, true)) {
                snapshotRead.countDown()
                assertTrue(release.await(10, TimeUnit.SECONDS), "Envelope barrier timed out")
            }
        })).create(clockProvider = FixedClockProvider(NOW))
        val contender = JooqLimitguardFactory(ObservedDataSource(fixture.source, before = { sql ->
            if (sql.contains("pg_advisory_xact_lock") || sql.contains("for update") ||
                isSnapshot(sql, "funding_source_product")) contenderRead.countDown()
        })).create(clockProvider = FixedClockProvider(NOW))
        val executor = Executors.newFixedThreadPool(2)
        fun admit(facade: Limitguard) { facade.tenantCatalogService.createLimitConfiguration(configuration) }
        fun grow(facade: Limitguard) { facade.tenantCatalogService.registerFundingSourceProduct(growth) }

        try {
            val first = executor.submit<Result<Unit>> {
                runCatching { if (configurationFirst) admit(holder) else grow(holder) }
            }
            assertTrue(snapshotRead.await(10, TimeUnit.SECONDS), "First writer did not read its envelope snapshot")
            val second = executor.submit<Result<Unit>> {
                try {
                    runCatching { if (configurationFirst) grow(contender) else admit(contender) }
                } finally {
                    contenderDone.countDown()
                }
            }
            assertTrue(contenderRead.await(10, TimeUnit.SECONDS), "Contending writer did not start")
            if (!contenderDone.await(250, TimeUnit.MILLISECONDS)) awaitEnvelopeWriter(fixture.source)
            release.countDown()
            first.get(10, TimeUnit.SECONDS).getOrThrow()
            assertIs<ValidationException>(second.get(10, TimeUnit.SECONDS).exceptionOrNull())

            assertEquals(parent, s.findConfiguration("PARENT"))
            if (configurationFirst) {
                val child = requireNotNull(s.findConfiguration("CHILD"))
                assertEquals(parent.id, child.parentConfigurationId)
                assertEquals(false, child.isActive)
                if (categoryChild) assertEquals(FundingSourceCategory.SAVINGS, child.fundingSourceCategory)
                else assertEquals(family.id, child.fundingSourceProductId)
                assertNull(s.findProduct("OUTSIDE"))
                assertEquals(FundingSourceCategory.TRANSACTIONAL, contender.tenantCatalogService.registerFundingSourceProduct(
                    growth.copy(category = FundingSourceCategory.TRANSACTIONAL),
                ).category)
            } else {
                assertNull(s.findConfiguration("CHILD"))
                assertEquals(if (categoryChild) null else family.id, requireNotNull(s.findProduct("OUTSIDE")).parentId)
                assertEquals(parent.id, contender.tenantCatalogService.createLimitConfiguration(configuration.copy(
                    fundingScope = parent.fundingScope, fundingSourceCategory = parent.fundingSourceCategory,
                    fundingSourceProductId = parent.fundingSourceProductId,
                )).parentConfigurationId)
            }
        } finally {
            release.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    private fun fixture(): Fixture {
        val schema = "envelope_${UUID.randomUUID().toString().replace("-", "")}"
        Flyway.configure().dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .schemas(schema).defaultSchema(schema).locations("classpath:db/limitguard/migration").load().migrate()
        val source = PGSimpleDataSource().apply {
            setURL(postgres.jdbcUrl)
            user = postgres.username
            password = postgres.password
            currentSchema = schema
        }
        val facade = JooqLimitguardFactory(source).create(clockProvider = FixedClockProvider(NOW))
        return Fixture(source, FundingEnvelopeScenario(facade, NOW))
    }

    private fun isSnapshot(sql: String, table: String) =
        sql.startsWith("select") && sql.contains("from \"$table\"") && sql.contains("order by")

    private fun execute(source: DataSource, sql: String, vararg values: Any?) = source.connection.use { connection ->
        connection.prepareStatement(sql).use { statement ->
            values.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
            statement.executeUpdate()
        }
    }

    private fun awaitEnvelopeWriter(source: DataSource) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        source.connection.use { connection ->
            while (System.nanoTime() < deadline) {
                val waiting = connection.createStatement().use { statement ->
                    statement.executeQuery("""
                        select exists (
                            select 1 from pg_stat_activity
                            where datname = current_database() and pid <> pg_backend_pid()
                              and wait_event_type = 'Lock' and query ilike '%pg_advisory_xact_lock%'
                        )
                    """.trimIndent()).use { it.next(); it.getBoolean(1) }
                }
                if (waiting) return
                Thread.sleep(20)
            }
        }
        error("Contending writer did not wait for the funding envelope invariant")
    }

    private data class Fixture(val source: DataSource, val scenario: FundingEnvelopeScenario)

    companion object {
        private val NOW = Instant.parse("2026-10-10T10:00:00Z")

        @Container
        @JvmField
        val postgres = PostgreSQLContainer("postgres:18-alpine")
    }
}
