package io.github.sepfins.limitguard.infrastructure.jooq

import io.github.sepfins.limitguard.api.AuthorizationDecision
import io.github.sepfins.limitguard.api.OperationCapacityStatus
import io.github.sepfins.limitguard.api.commands.CaptureTransactionCommand
import io.github.sepfins.limitguard.api.commands.ReleaseAuthorizationCommand
import io.github.sepfins.limitguard.api.commands.ReverseTransactionCommand
import io.github.sepfins.limitguard.api.errors.ConflictException
import io.github.sepfins.limitguard.api.errors.NotFoundException
import io.github.sepfins.limitguard.api.errors.ValidationException
import io.github.sepfins.limitguard.api.queries.FindOperationTypesByCodeQuery
import io.github.sepfins.limitguard.api.queries.GetOperationCapacityQuery
import io.github.sepfins.limitguard.support.FixedClockProvider
import io.github.sepfins.limitguard.support.OperationSubtreeScenario
import io.github.sepfins.limitguard.support.persistence.ObservedDataSource
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.FlywayException
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
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@Testcontainers
@Timeout(30)
class OperationSubtreePersistenceTest {
    @Test
    fun `PostgreSQL rejects taxonomy edits that make a global subtree tenant private`() {
        val fixture = fixture()
        val s = fixture.scenario
        val local = s.operation("LOCAL")
        val global = s.operation("GLOBAL", global = true)
        val child = s.operation("CHILD", global)

        val failure = assertFailsWith<SQLException> {
            execute(fixture.source, "update operation_type set parent_id = ? where id = ?", local, global)
        }
        assertEquals("23514", failure.sqlState)
        assertEquals(OperationCapacityStatus.UNLIMITED, s.capacity(child).status)
    }

    @Test
    fun `schema qualified taxonomy writes validate their own catalog regardless of caller search path`() {
        val fixture = fixture()
        val s = fixture.scenario
        val parent = s.operation("PARENT")
        s.operation("CHILD", parent)
        fixture.source.connection.use { connection ->
            connection.createStatement().use { it.execute("set search_path = public") }
            val failure = assertFailsWith<SQLException> {
                update(connection, "update ${fixture.schema}.operation_type set is_leaf = true where id = ?", parent)
            }
            assertEquals("23514", failure.sqlState)
            update(connection, "update ${fixture.schema}.operation_type set name = 'Renamed' where id = ?", parent)
        }
        assertEquals(AuthorizationDecision.ALLOWED, s.authorize(parent).decision)
    }

    @Test
    fun `corrupt stored paths return public errors before unmatched ALLOW can record a decision`() {
        for (corruption in listOf("HIDDEN", "MISSING", "CYCLE", "SELF_CYCLE", "GLOBAL_PRIVATE", "LEAF")) {
            val fixture = fixture()
            val s = fixture.scenario
            val root = s.operation("ROOT", global = corruption == "GLOBAL_PRIVATE")
            val middle = s.operation("MIDDLE", root)
            val selected = s.operation("SELECTED", middle)
            val other = OperationSubtreeScenario(s.facade, NOW)
            val hidden = other.operation("PRIVATE")
            val local = s.operation("LOCAL")
            val parent = when (corruption) {
                "HIDDEN" -> hidden
                "MISSING" -> UUID.randomUUID()
                "CYCLE" -> selected
                "SELF_CYCLE" -> root
                "GLOBAL_PRIVATE" -> local
                else -> null
            }
            corrupt(fixture.source, "update operation_type set parent_id = ?, is_leaf = ? where id = ?",
                parent, corruption == "LEAF", root)
            val command = s.command(selected, key = "invalid-path")

            if (corruption in listOf("HIDDEN", "MISSING")) {
                assertFailsWith<NotFoundException> { s.facade.limitEvaluationService.authorizeTransaction(command) }
                assertFailsWith<NotFoundException> { s.capacity(selected) }
                assertFailsWith<NotFoundException> { s.operation("FUTURE", selected) }
            } else {
                assertFailsWith<ConflictException> { s.facade.limitEvaluationService.authorizeTransaction(command) }
                assertFailsWith<ConflictException> { s.capacity(selected) }
                assertFailsWith<ConflictException> { s.operation("FUTURE", selected) }
            }
            corrupt(fixture.source, "update operation_type set parent_id = null, is_leaf = false where id = ?", root)
            assertEquals(AuthorizationDecision.ALLOWED, s.facade.limitEvaluationService.authorizeTransaction(command).decision)
            assertEquals(OperationCapacityStatus.UNLIMITED, s.capacity(selected).status)
        }
    }

    @Test
    fun `V4 reports invalid legacy taxonomy without repairing nodes or historical decisions`() {
        for (corruption in listOf("HIDDEN", "MISSING", "CYCLE", "SELF_CYCLE", "GLOBAL_PRIVATE", "LEAF")) {
            val fixture = fixture(target = "3")
            val s = fixture.scenario
            // Seed legacy rows directly: current catalog admission requires the V4 guard.
            fun legacyOperation(code: String, parent: UUID? = null, global: Boolean = false, tenant: UUID = s.tenant): UUID {
                val id = UUID.randomUUID()
                execute(fixture.source,
                    "insert into operation_type (id, tenant_id, parent_id, code, name, is_global) values (?, ?, ?, ?, ?, ?)",
                    id, if (global) null else tenant, parent, code, code, global)
                return id
            }
            val root = legacyOperation("ROOT", global = corruption == "GLOBAL_PRIVATE")
            val selected = legacyOperation("SELECTED", root)
            val originalCommand = s.command(selected, key = "historical")
            val original = s.facade.limitEvaluationService.authorizeTransaction(originalCommand)
            val other = OperationSubtreeScenario(s.facade, NOW)
            val parent = when (corruption) {
                "HIDDEN" -> legacyOperation("PRIVATE", tenant = other.tenant)
                "MISSING" -> UUID.randomUUID()
                "CYCLE" -> selected
                "SELF_CYCLE" -> root
                "GLOBAL_PRIVATE" -> legacyOperation("LOCAL")
                else -> null
            }
            corrupt(fixture.source, "update operation_type set parent_id = ?, is_leaf = ? where id = ?",
                parent, corruption == "LEAF", root)

            val failure = assertFailsWith<FlywayException> { migrations(fixture.schema) }
            assertTrue(failure.message.orEmpty().contains("Invalid operation taxonomy"))
            assertEquals(original, s.facade.limitEvaluationService.authorizeTransaction(originalCommand))
            assertFailsWith<FlywayException> { migrations(fixture.schema) }

            execute(fixture.source, "update operation_type set parent_id = null, is_leaf = false where id = ?", root)
            assertEquals(1, migrations(fixture.schema, target = "4").migrationsExecuted)
            assertEquals(original, s.facade.limitEvaluationService.authorizeTransaction(originalCommand))
            assertEquals(OperationCapacityStatus.UNLIMITED, s.capacity(selected).status)
        }
    }

    @Test
    fun `a corrupt shared leaf with hidden children fails without returning private child identities`() {
        val fixture = fixture()
        val s = fixture.scenario
        val global = s.operation("GLOBAL", global = true)
        val other = OperationSubtreeScenario(s.facade, NOW)
        other.operation("PRIVATE_CHILD", global)
        corrupt(fixture.source, "update operation_type set is_leaf = true where id = ?", global)

        assertFailsWith<ConflictException> { s.authorize(global) }
        assertFailsWith<ConflictException> { s.capacity(global) }
        assertEquals(emptyList(), s.facade.limitInquiryService.findOperationTypesByCode(
            FindOperationTypesByCodeQuery(s.tenant, "PRIVATE_CHILD"),
        ))
    }

    @Test
    fun `a leaf declaration in flight blocks and rejects a concurrent public child registration`() {
        val fixture = fixture()
        val s = fixture.scenario
        val parent = s.operation("PARENT")
        val executor = Executors.newSingleThreadExecutor()
        try {
            fixture.source.connection.use { blocker ->
                blocker.autoCommit = false
                update(blocker, "update operation_type set is_leaf = true where id = ?", parent)
                val child = executor.submit<UUID> { s.operation("CHILD", parent) }
                awaitTaxonomyWriter(fixture.source)
                blocker.commit()
                val failure = assertFailsWith<ExecutionException> { child.get(10, TimeUnit.SECONDS) }
                assertTrue(failure.cause is ValidationException)
            }
            assertFailsWith<ValidationException> { s.operation("RETRY_CHILD", parent) }
            assertEquals(AuthorizationDecision.ALLOWED, s.authorize(parent).decision)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `concurrent child growth prevents stale leaf declarations at read committed and repeatable read`() {
        for (isolation in listOf(Connection.TRANSACTION_READ_COMMITTED, Connection.TRANSACTION_REPEATABLE_READ)) {
            val fixture = fixture()
            val s = fixture.scenario
            val parent = s.operation("PARENT")
            val childId = UUID.randomUUID()
            val executor = Executors.newSingleThreadExecutor()
            try {
                fixture.source.connection.use { blocker ->
                    blocker.autoCommit = false
                    update(blocker, "insert into operation_type (id, tenant_id, parent_id, code, name) values (?, ?, ?, 'CHILD', 'Child')",
                        childId, s.tenant, parent)
                    val declaration = executor.submit {
                        fixture.source.connection.use { writer ->
                            writer.transactionIsolation = isolation
                            writer.autoCommit = false
                            writer.createStatement().use { statement ->
                                statement.executeQuery("select id from operation_type").use { it.next() }
                            }
                            update(writer, "update operation_type set is_leaf = true where id = ?", parent)
                            writer.commit()
                        }
                    }
                    awaitTaxonomyWriter(fixture.source)
                    blocker.commit()
                    val failure = assertFailsWith<ExecutionException> { declaration.get(10, TimeUnit.SECONDS) }
                    val sqlFailure = failure.cause as SQLException
                    assertEquals(if (isolation == Connection.TRANSACTION_READ_COMMITTED) "23514" else "40001", sqlFailure.sqlState)
                }
                assertEquals(AuthorizationDecision.ALLOWED, s.authorize(childId).decision)
                val parentNode = s.facade.limitInquiryService.findOperationTypesByCode(FindOperationTypesByCodeQuery(s.tenant, "PARENT")).single()
                assertEquals(false, parentNode.isLeaf)
            } finally {
                executor.shutdownNow()
            }
        }
    }

    @Test
    fun `PostgreSQL facade shares live global and local subtree coverage with advisory capacity`() {
        val fixture = fixture()
        val s = fixture.scenario
        val global = s.operation("TRANSFER", global = true)
        val selected = s.operation("SELECTED", global, global = true)
        val child = s.operation("CHILD", selected)
        val sibling = s.operation("SIBLING", global)
        val privateSameCode = s.operation("TRANSFER")
        val rule = s.grant(selected)
        val globalRule = s.grant(global)
        val futureGrandchild = s.operation("GRANDCHILD", child, leaf = true)
        val expected = setOf(rule, globalRule)

        for (operation in listOf(selected, child, futureGrandchild)) {
            assertEquals(expected, s.capacity(operation).applicableLimits.map { it.limitRuleId }.toSet())
            val result = s.authorize(operation)
            assertEquals(AuthorizationDecision.ALLOWED, result.decision)
            assertEquals(expected, result.matchedRules.map { it.id }.toSet())
        }
        assertEquals(listOf(globalRule), s.capacity(sibling).applicableLimits.map { it.limitRuleId })
        assertEquals(emptyList(), s.capacity(privateSameCode).applicableLimits)
        assertEquals(emptyList(), s.authorize(privateSameCode).matchedRules)
        assertEquals(setOf(global, privateSameCode), s.facade.limitInquiryService.findOperationTypesByCode(
            FindOperationTypesByCodeQuery(s.tenant, "TRANSFER"),
        ).map { it.id }.toSet())
    }

    @Test
    fun `authorization and capacity keep one ancestry snapshot across a concurrent atomic reparent`() {
        for (authorization in listOf(true, false)) {
            val fixture = fixture()
            val s = fixture.scenario
            val originalRoot = s.operation("ORIGINAL_ROOT")
            val middle = s.operation("MIDDLE", originalRoot)
            val selected = s.operation("SELECTED", middle)
            val newRoot = s.operation("NEW_ROOT")
            val unrelated = s.operation("UNRELATED")
            val originalCap = s.grant(originalRoot, amount = "25", perTransaction = true)
            val newCap = s.grant(newRoot, amount = "25", perTransaction = true)
            val ancestryRead = CountDownLatch(1)
            val continueRead = CountDownLatch(1)
            val pauseOnce = AtomicBoolean()
            val observed = ObservedDataSource(fixture.source, after = { sql ->
                if (sql.contains("from \"operation_type\"") && pauseOnce.compareAndSet(false, true)) {
                    ancestryRead.countDown()
                    assertTrue(continueRead.await(10, TimeUnit.SECONDS), "ancestry read barrier timed out")
                }
            })
            val facade = JooqLimitguardFactory(observed).create(clockProvider = FixedClockProvider(NOW))
            val executor = Executors.newSingleThreadExecutor()
            try {
                val evaluation = executor.submit {
                    if (authorization) {
                        val result = facade.limitEvaluationService.authorizeTransaction(s.command(selected))
                        assertEquals(AuthorizationDecision.DENIED, result.decision)
                        assertEquals(listOf(originalCap), result.matchedRules.map { it.id })
                    } else {
                        val result = facade.limitInquiryService.getOperationCapacity(GetOperationCapacityQuery(
                            s.tenant, s.owner, s.source, selected, s.channel, "EUR",
                        ))
                        assertEquals(BigDecimal("25.0000"), result.effectiveAvailableAmount)
                        assertEquals(listOf(originalCap), result.applicableLimits.map { it.limitRuleId })
                    }
                }
                assertTrue(ancestryRead.await(10, TimeUnit.SECONDS), "evaluation did not reach the ancestry read")
                fixture.source.connection.use { writer ->
                    writer.autoCommit = false
                    update(writer, "update operation_type set parent_id = ? where id = ?", newRoot, selected)
                    update(writer, "update operation_type set parent_id = ? where id = ?", unrelated, middle)
                    writer.commit()
                }
                continueRead.countDown()
                evaluation.get(10, TimeUnit.SECONDS)
                assertEquals(listOf(newCap), s.capacity(selected).applicableLimits.map { it.limitRuleId })
                assertEquals(AuthorizationDecision.DENIED, s.authorize(selected).decision)
            } finally {
                continueRead.countDown()
                executor.shutdownNow()
            }
        }
    }

    @Test
    fun `PostgreSQL mutations use original buckets after live taxonomy and rule coverage changes`() {
        val fixture = fixture()
        val s = fixture.scenario
        val originalRoot = s.operation("ROOT")
        val selected = s.operation("SELECTED", originalRoot)
        val originalRule = s.grant(originalRoot)
        val command = s.command(selected, amount = "80", key = "original")
        val original = s.facade.limitEvaluationService.authorizeTransaction(command)
        val payment = requireNotNull(original.paymentTransactionId)
        val future = s.operation("FUTURE", selected, leaf = true)
        assertEquals(listOf(originalRule), s.capacity(future).applicableLimits.map { it.limitRuleId })
        val newRoot = s.operation("UNRELATED")
        execute(fixture.source, "update operation_type set parent_id = ? where id = ?", newRoot, selected)
        assertEquals(OperationCapacityStatus.UNLIMITED, s.capacity(future).status)
        val futureRule = s.grant(selected, priority = 10, isolated = true)
        assertEquals(listOf(futureRule), s.capacity(future).applicableLimits.map { it.limitRuleId })

        val reloaded = JooqLimitguardFactory(fixture.source).create(clockProvider = FixedClockProvider(NOW))
        assertEquals(original, reloaded.limitEvaluationService.authorizeTransaction(command))
        val mutations = listOf(
            reloaded.limitEvaluationService.captureTransaction(CaptureTransactionCommand(s.tenant, payment, BigDecimal("30"))),
            reloaded.limitEvaluationService.releaseAuthorization(ReleaseAuthorizationCommand(s.tenant, payment)),
            reloaded.limitEvaluationService.reverseTransaction(ReverseTransactionCommand(s.tenant, payment, BigDecimal("10"))),
        )
        for (mutation in mutations) {
            assertEquals(listOf(originalRule), mutation.usageEvents.map { it.limitRuleId })
            assertEquals(s.usage(originalRule).usageBucketId, mutation.usageEvents.single().usageBucketId)
        }
        assertEquals(BigDecimal("0.0000"), s.usage(originalRule).reservedAmount)
        assertEquals(BigDecimal("20.0000"), s.usage(originalRule).consumedAmount)
        assertEquals(BigDecimal("0.0000"), s.usage(futureRule).reservedAmount)
        assertEquals(BigDecimal("0.0000"), s.usage(futureRule).consumedAmount)
    }

    private fun fixture(target: String? = null): Fixture {
        val schema = "operations_${UUID.randomUUID().toString().replace("-", "")}"
        migrations(schema, target)
        val source = PGSimpleDataSource().apply {
            setURL(postgres.jdbcUrl)
            user = postgres.username
            password = postgres.password
            currentSchema = schema
        }
        val facade = JooqLimitguardFactory(source).create(clockProvider = FixedClockProvider(NOW))
        return Fixture(source, OperationSubtreeScenario(facade, NOW), schema)
    }

    private fun migrations(schema: String, target: String? = null) = Flyway.configure()
        .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        .schemas(schema).defaultSchema(schema).locations("classpath:db/limitguard/migration")
        .apply { if (target != null) target(target) }.load().migrate()

    private fun execute(source: DataSource, sql: String, vararg values: Any?) = source.connection.use { connection ->
        update(connection, sql, *values)
    }

    private fun corrupt(source: DataSource, sql: String, vararg values: Any?) = source.connection.use { connection ->
        connection.autoCommit = false
        connection.createStatement().use { it.execute("set local session_replication_role = replica") }
        update(connection, sql, *values)
        connection.commit()
    }

    private fun update(connection: Connection, sql: String, vararg values: Any?) =
        connection.prepareStatement(sql).use { statement ->
            values.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
            statement.executeUpdate()
        }

    private fun awaitTaxonomyWriter(source: DataSource) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        source.connection.use { connection ->
            while (System.nanoTime() < deadline) {
                val waiting = connection.createStatement().use { statement ->
                    statement.executeQuery("""
                        select exists (
                            select 1 from pg_stat_activity
                            where datname = current_database() and pid <> pg_backend_pid()
                              and wait_event_type = 'Lock' and query ilike '%operation_type%'
                        )
                    """.trimIndent()).use { it.next(); it.getBoolean(1) }
                }
                if (waiting) return
                Thread.sleep(20)
            }
        }
        error("Concurrent taxonomy writer did not wait for the in-flight mutation")
    }

    private data class Fixture(val source: DataSource, val scenario: OperationSubtreeScenario, val schema: String)

    companion object {
        private val NOW = Instant.parse("2026-10-05T10:00:00Z")

        @Container
        @JvmField
        val postgres = PostgreSQLContainer("postgres:18-alpine")
    }
}
