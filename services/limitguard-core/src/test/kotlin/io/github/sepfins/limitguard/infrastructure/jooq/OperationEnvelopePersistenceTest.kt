package io.github.sepfins.limitguard.infrastructure.jooq

import io.github.sepfins.limitguard.api.Limitguard
import io.github.sepfins.limitguard.api.commands.RegisterOperationTypeCommand
import io.github.sepfins.limitguard.api.commands.SetLimitConfigurationActiveCommand
import io.github.sepfins.limitguard.api.commands.UpdateLimitConfigurationCommand
import io.github.sepfins.limitguard.api.errors.InfrastructureException
import io.github.sepfins.limitguard.api.errors.ValidationException
import io.github.sepfins.limitguard.support.FixedClockProvider
import io.github.sepfins.limitguard.support.FundingEnvelopeScenario
import io.github.sepfins.limitguard.support.persistence.ObservedDataSource
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import java.math.BigDecimal
import java.sql.Connection
import java.sql.SQLException
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Tag("database")
@Testcontainers
@Timeout(30)
class OperationEnvelopePersistenceTest {
    @Test
    fun `configuration admission excludes relevant operation growth through commit`() = contend(configurationFirst = true)

    @Test
    fun `operation growth excludes configuration admission until fresh coverage is visible`() = contend(configurationFirst = false)

    @Test
    fun `disabled tenant configuration admission excludes another tenants global descendant growth`() =
        contend(configurationFirst = true, global = true)

    @Test
    fun `global descendant growth excludes another tenants configuration admission through commit`() =
        contend(configurationFirst = false, global = true)

    @Test
    fun `unsafe isolation rejects operation growth configuration admission and maintenance before writes`() {
        val fixture = fixture()
        val s = fixture.scenario
        val root = s.registerOperation("ROOT")
        val parent = s.configuration("PARENT", operationId = root.id)
        val child = s.configuration("CHILD", parent.id, operationId = root.id, active = false)
        for (isolation in listOf(Connection.TRANSACTION_REPEATABLE_READ, Connection.TRANSACTION_SERIALIZABLE)) {
            val source = object : DataSource by fixture.source {
                override fun getConnection(): Connection = fixture.source.connection.apply { transactionIsolation = isolation }
            }
            val unsafe = JooqLimitguardFactory(source).create(clockProvider = FixedClockProvider(NOW))
            val actions = listOf<() -> Unit>(
                { unsafe.tenantCatalogService.registerOperationType(RegisterOperationTypeCommand(s.tenant, "UNSAFE", "Unsafe", parentId = root.id)) },
                { unsafe.tenantCatalogService.createLimitConfiguration(s.configurationCommand("UNSAFE", parent.id, operationId = root.id)) },
                { unsafe.tenantCatalogService.updateLimitConfiguration(UpdateLimitConfigurationCommand(s.tenant, child.id, "Changed", null, BigDecimal.ONE, emptyMap())) },
                { unsafe.tenantCatalogService.setLimitConfigurationActive(SetLimitConfigurationActiveCommand(s.tenant, child.id, false)) },
            )
            actions.forEach { action ->
                val failure = assertFailsWith<InfrastructureException> { action() }
                assertTrue(failure.message.orEmpty().contains("READ COMMITTED"))
                assertEquals(emptyList(), s.findOperations("UNSAFE"))
                assertNull(s.findConfiguration("UNSAFE"))
                assertEquals(child, s.findConfiguration("CHILD"))
            }
        }
        val safe = s.registerOperation("SAFE", root.id)
        assertEquals(child.id, s.configuration("SAFE", child.id, operationId = safe.id).parentConfigurationId)
    }

    @Test
    fun `post-write failure rolls back global operation growth and releases the shared guard`() {
        val fixture = fixture()
        val s = fixture.scenario
        val other = FundingEnvelopeScenario(s.facade, NOW)
        val root = s.registerOperation("ROOT", global = true)
        val parent = s.configuration("PARENT", operationId = root.id, active = false)
        val child = s.configuration("CHILD", parent.id, operationId = root.id, active = false)
        val command = RegisterOperationTypeCommand(other.tenant, "NEW", "New", parentId = root.id, isGlobal = true)
        val failing = JooqLimitguardFactory(ObservedDataSource(fixture.source, after = { sql ->
            if (sql.startsWith("insert into \"operation_type\"")) throw SQLException("Injected failure after operation write", "XX000")
        })).create(clockProvider = FixedClockProvider(NOW))

        assertFailsWith<InfrastructureException> { failing.tenantCatalogService.registerOperationType(command) }

        assertEquals(emptyList(), s.findOperations("NEW"))
        assertEquals(emptyList(), other.findOperations("NEW"))
        assertEquals(parent, s.findConfiguration("PARENT"))
        assertEquals(child, s.findConfiguration("CHILD"))
        val registered = s.facade.tenantCatalogService.registerOperationType(command)
        assertEquals(listOf(registered), s.findOperations("NEW"))
        assertEquals(child.id, s.configuration("RETRY_PROBE", child.id, operationId = registered.id).parentConfigurationId)
    }

    @Test
    fun `post-write configuration failure leaves no admission and releases both envelope locks`() {
        val fixture = fixture()
        val s = fixture.scenario
        val root = s.registerOperation("ROOT")
        val parent = s.configuration("PARENT", operationId = root.id)
        val command = s.configurationCommand("CHILD", parent.id, operationId = root.id)
        val failing = JooqLimitguardFactory(ObservedDataSource(fixture.source, after = { sql ->
            if (sql.startsWith("insert into \"limit_configuration\"")) throw SQLException("Injected failure after configuration write", "XX000")
        })).create(clockProvider = FixedClockProvider(NOW))

        assertFailsWith<InfrastructureException> { failing.tenantCatalogService.createLimitConfiguration(command) }

        assertNull(s.findConfiguration("CHILD"))
        assertEquals(parent, s.findConfiguration("PARENT"))
        assertEquals(parent.id, s.facade.tenantCatalogService.createLimitConfiguration(command).parentConfigurationId)
        assertEquals(root.id, s.registerOperation("NEXT", root.id).parentId)
    }

    @Test
    fun `a missing catalog guard fails closed without accepting operation or configuration writes`() {
        val fixture = fixture()
        val s = fixture.scenario
        val root = s.registerOperation("ROOT")
        val parent = s.configuration("PARENT", operationId = root.id)
        execute(fixture.source, "delete from operation_type_catalog_guard")

        assertFailsWith<InfrastructureException> { s.registerOperation("NEW", root.id) }
        assertFailsWith<InfrastructureException> { s.configuration("CHILD", parent.id, operationId = root.id) }
        assertEquals(emptyList(), s.findOperations("NEW"))
        assertNull(s.findConfiguration("CHILD"))
        assertEquals(parent, s.findConfiguration("PARENT"))
        execute(fixture.source, "insert into operation_type_catalog_guard (singleton) values (true)")
        assertEquals(root.id, s.registerOperation("NEW", root.id).parentId)
        assertEquals(parent.id, s.configuration("CHILD", parent.id, operationId = root.id).parentConfigurationId)
    }

    @Test
    fun `legacy disabled tenant coverage blocks global growth local growth and full-chain maintenance until explicit correction`() {
        val fixture = fixture()
        val s = fixture.scenario
        val other = FundingEnvelopeScenario(s.facade, NOW)
        val transfer = s.registerOperation("TRANSFER", global = true)
        val cash = s.registerOperation("CASH", global = true)
        val local = s.registerOperation("LOCAL", transfer.id)
        val root = s.configuration("ROOT", active = false)
        val parent = s.configuration("PARENT", root.id, operationId = transfer.id, active = false)
        val child = s.configuration("CHILD", parent.id, operationId = local.id, active = false)
        execute(fixture.source, "update limit_configuration set operation_type_id = ? where id = ?", cash.id, root.id)
        val invalidRoot = s.findConfiguration("ROOT")

        for (action in listOf("admission", "local", "global", "description", "activation")) {
            val failure = assertFailsWith<ValidationException> {
                when (action) {
                    "admission" -> s.configuration("NEW", child.id, operationId = local.id)
                    "local" -> s.registerOperation("NEW", transfer.id)
                    "global" -> other.registerOperation("NEW", transfer.id, global = true)
                    "description" -> s.facade.tenantCatalogService.updateLimitConfiguration(UpdateLimitConfigurationCommand(
                        s.tenant, child.id, "Changed", null, BigDecimal.ONE, emptyMap(),
                    ))
                    else -> s.facade.tenantCatalogService.setLimitConfigurationActive(SetLimitConfigurationActiveCommand(s.tenant, child.id, true))
                }
            }
            assertTrue(failure.message.orEmpty().contains("correct the operation envelope explicitly"))
            assertTrue(failure.message.orEmpty().contains(parent.id.toString()))
            assertTrue(failure.message.orEmpty().contains(root.id.toString()))
            assertEquals(invalidRoot, s.findConfiguration("ROOT"))
            assertEquals(parent, s.findConfiguration("PARENT"))
            assertEquals(child, s.findConfiguration("CHILD"))
            assertNull(s.findConfiguration("NEW"))
            assertEquals(emptyList(), s.findOperations("NEW"))
            assertEquals(emptyList(), other.findOperations("NEW"))
        }
        execute(fixture.source, "update limit_configuration set operation_type_id = null where id = ?", root.id)
        assertEquals(root, s.findConfiguration("ROOT"))
        val registered = other.registerOperation("NEW", transfer.id, global = true)
        assertEquals(listOf(registered), s.findOperations("NEW"))
        assertEquals(parent.id, s.configuration("NEW", parent.id, operationId = registered.id).parentConfigurationId)
    }

    private fun contend(configurationFirst: Boolean, global: Boolean = false) {
        val fixture = fixture()
        val s = fixture.scenario
        val other = FundingEnvelopeScenario(s.facade, NOW)
        val root = s.registerOperation("TRANSFER", global = true)
        val parent = s.configuration("PARENT", operationId = root.id, active = false)
        val configuration = s.configurationCommand("CHILD", parent.id, operationId = root.id, active = false)
        val growth = RegisterOperationTypeCommand(
            if (global) other.tenant else s.tenant, "NEW", "New", parentId = root.id, isGlobal = global,
        )
        val snapshotRead = CountDownLatch(1)
        val release = CountDownLatch(1)
        val contenderStarted = CountDownLatch(1)
        val observed = AtomicBoolean()
        val holder = JooqLimitguardFactory(ObservedDataSource(fixture.source, after = { sql ->
            val snapshot = if (configurationFirst) sql.startsWith("with recursive") && sql.contains("\"operation_type\"")
                else sql.startsWith("select") && sql.contains("from \"limit_configuration\"") && sql.contains("order by")
            if (snapshot && observed.compareAndSet(false, true)) {
                snapshotRead.countDown()
                assertTrue(release.await(10, TimeUnit.SECONDS), "Operation envelope barrier timed out")
            }
        })).create(clockProvider = FixedClockProvider(NOW))
        val contender = JooqLimitguardFactory(ObservedDataSource(fixture.source, before = { sql ->
            if (sql.startsWith("update \"operation_type_catalog_guard\"")) contenderStarted.countDown()
        })).create(clockProvider = FixedClockProvider(NOW))
        val executor = Executors.newFixedThreadPool(2)
        fun admit(facade: Limitguard) { facade.tenantCatalogService.createLimitConfiguration(configuration) }
        fun grow(facade: Limitguard) { facade.tenantCatalogService.registerOperationType(growth) }

        try {
            val first = executor.submit { if (configurationFirst) admit(holder) else grow(holder) }
            assertTrue(snapshotRead.await(10, TimeUnit.SECONDS), "First writer did not read operation coverage")
            val second = executor.submit { if (configurationFirst) grow(contender) else admit(contender) }
            assertTrue(contenderStarted.await(10, TimeUnit.SECONDS), "Contending writer did not start")
            awaitEnvelopeWriter(fixture.source)
            release.countDown()
            first.get(10, TimeUnit.SECONDS)
            second.get(10, TimeUnit.SECONDS)

            val child = requireNotNull(s.findConfiguration("CHILD"))
            assertEquals(parent.id, child.parentConfigurationId)
            assertEquals(root.id, child.operationTypeId)
            assertEquals(false, child.isActive)
            val registered = s.findOperations("NEW").single()
            assertEquals(root.id, registered.parentId)
            assertEquals(global, registered.isGlobal)
            assertEquals(child.id, s.configuration("COMMITTED_PROBE", child.id, operationId = registered.id).parentConfigurationId)
            assertEquals(parent, s.findConfiguration("PARENT"))
        } finally {
            release.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    private fun fixture(): Fixture {
        val schema = "operation_envelope_${UUID.randomUUID().toString().replace("-", "")}"
        Flyway.configure().dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .schemas(schema).defaultSchema(schema).locations("classpath:db/limitguard/migration").load().migrate()
        val source = PGSimpleDataSource().apply {
            setURL(postgres.jdbcUrl)
            user = postgres.username
            password = postgres.password
            currentSchema = schema
        }
        return Fixture(source, FundingEnvelopeScenario(JooqLimitguardFactory(source).create(clockProvider = FixedClockProvider(NOW)), NOW))
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
                              and wait_event_type = 'Lock' and query ilike '%operation_type_catalog_guard%'
                        )
                    """.trimIndent()).use { it.next(); it.getBoolean(1) }
                }
                if (waiting) return
                Thread.sleep(20)
            }
        }
        error("Contending writer did not wait for the operation envelope invariant")
    }

    private fun execute(source: DataSource, sql: String, vararg values: Any?) = source.connection.use { connection ->
        connection.prepareStatement(sql).use { statement ->
            values.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
            statement.executeUpdate()
        }
    }

    private data class Fixture(val source: DataSource, val scenario: FundingEnvelopeScenario)

    companion object {
        private val NOW = Instant.parse("2026-10-10T10:00:00Z")

        @Container
        @JvmField
        val postgres = PostgreSQLContainer("postgres:18-alpine")
    }
}
