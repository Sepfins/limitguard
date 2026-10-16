package io.github.sepfins.limitguard.infrastructure.jooq

import io.github.sepfins.limitguard.api.Limitguard
import io.github.sepfins.limitguard.api.AuthorizationDecision
import io.github.sepfins.limitguard.api.LimitRuleChangeStatus
import io.github.sepfins.limitguard.api.commands.LimitRuleChangeOperation
import io.github.sepfins.limitguard.api.commands.AddChannelGroupMemberCommand
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
import java.sql.Connection
import java.sql.SQLException
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Testcontainers
@Timeout(30)
class ChannelEnvelopePersistenceTest {
    @Test
    fun `group child admission commits before contending membership growth revalidates disabled envelopes`() =
        contend(configurationFirst = true)

    @Test
    fun `membership growth commits before group child admission reads its current coverage`() =
        contend(configurationFirst = false)

    @Test
    fun `empty group admission under a single channel commits before incompatible membership growth`() =
        contend(configurationFirst = true, singleParent = true)

    @Test
    fun `membership growth commits before admission under a single channel revalidates coverage`() =
        contend(configurationFirst = false, singleParent = true)

    @Test
    fun `separate membership writers refresh coverage after the parent group growth commits`() {
        val fixture = fixture()
        val s = fixture.scenario
        val api = s.registerChannel("API")
        val web = s.registerChannel("WEB", active = false)
        val parentGroup = s.channelGroup("PARENT_GROUP", api.id)
        val childGroup = s.channelGroup("CHILD_GROUP", api.id)
        val parent = s.configuration("PARENT", channelGroupId = parentGroup.id, active = false)
        val child = s.configuration("CHILD", parent.id, channelGroupId = childGroup.id, active = false)
        val snapshotRead = CountDownLatch(1)
        val release = CountDownLatch(1)
        val contenderStarted = CountDownLatch(1)
        val observed = AtomicBoolean()
        val holder = JooqLimitguardFactory(ObservedDataSource(fixture.source, after = { sql ->
            if (isSnapshot(sql, "channel_group_member") && observed.compareAndSet(false, true)) {
                snapshotRead.countDown()
                assertTrue(release.await(10, TimeUnit.SECONDS), "Membership barrier timed out")
            }
        })).create(clockProvider = FixedClockProvider(NOW))
        val contender = JooqLimitguardFactory(ObservedDataSource(fixture.source, before = { sql ->
            if (sql.contains("pg_advisory_xact_lock")) contenderStarted.countDown()
        })).create(clockProvider = FixedClockProvider(NOW))
        val executor = Executors.newFixedThreadPool(2)
        try {
            val first = executor.submit {
                holder.tenantCatalogService.addChannelGroupMember(AddChannelGroupMemberCommand(s.tenant, parentGroup.id, web.id))
            }
            assertTrue(snapshotRead.await(10, TimeUnit.SECONDS), "Parent growth did not read coverage")
            val second = executor.submit {
                contender.tenantCatalogService.addChannelGroupMember(AddChannelGroupMemberCommand(s.tenant, childGroup.id, web.id))
            }
            assertTrue(contenderStarted.await(10, TimeUnit.SECONDS), "Child growth did not start")
            awaitEnvelopeWriter(fixture.source)
            release.countDown()
            first.get(10, TimeUnit.SECONDS)
            second.get(10, TimeUnit.SECONDS)
            assertEquals(web.id, s.configuration("COMMITTED_MEMBER", child.id, channelId = web.id).channelId)
            assertEquals(parent, s.findConfiguration("PARENT"))
            assertEquals(child, s.findConfiguration("CHILD"))
        } finally {
            release.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `unsafe transaction snapshots reject membership admission before any writes`() {
        val fixture = fixture()
        val s = fixture.scenario
        val api = s.registerChannel("API")
        val group = s.channelGroup("EMPTY")
        val parent = s.configuration("PARENT", channelId = api.id)
        val child = s.configuration("CHILD", parent.id, channelGroupId = group.id)
        for (isolation in listOf(Connection.TRANSACTION_REPEATABLE_READ, Connection.TRANSACTION_SERIALIZABLE)) {
            val source = object : DataSource by fixture.source {
                override fun getConnection(): Connection = fixture.source.connection.apply { transactionIsolation = isolation }
            }
            val unsafe = JooqLimitguardFactory(source).create(clockProvider = FixedClockProvider(NOW))
            val failure = assertFailsWith<InfrastructureException> {
                unsafe.tenantCatalogService.addChannelGroupMember(AddChannelGroupMemberCommand(s.tenant, group.id, api.id))
            }
            assertTrue(failure.message.orEmpty().contains("READ COMMITTED"))
            assertFailsWith<ValidationException> { s.configuration("PROBE", child.id, channelId = api.id) }
            assertNull(s.findConfiguration("PROBE"))
            assertEquals(child, s.findConfiguration("CHILD"))
        }
        s.addChannelMember(group.id, api.id)
        assertEquals(api.id, s.configuration("PROBE", child.id, channelId = api.id).channelId)
    }

    @Test
    fun `post-write infrastructure failure rolls back membership and releases admission locks`() {
        val fixture = fixture()
        val s = fixture.scenario
        val api = s.registerChannel("API")
        val group = s.channelGroup("EMPTY")
        val parent = s.configuration("PARENT", channelId = api.id)
        val child = s.configuration("CHILD", parent.id, channelGroupId = group.id, active = false)
        val failing = JooqLimitguardFactory(ObservedDataSource(fixture.source, after = { sql ->
            if (sql.startsWith("insert into \"channel_group_member\"")) {
                throw SQLException("Injected failure after membership write", "XX000")
            }
        })).create(clockProvider = FixedClockProvider(NOW))

        assertFailsWith<InfrastructureException> {
            failing.tenantCatalogService.addChannelGroupMember(AddChannelGroupMemberCommand(s.tenant, group.id, api.id))
        }

        assertFailsWith<ValidationException> { s.configuration("PROBE", child.id, channelId = api.id) }
        assertNull(s.findConfiguration("PROBE"))
        assertEquals(child, s.findConfiguration("CHILD"))
        s.addChannelMember(group.id, api.id)
        assertEquals(api.id, s.configuration("PROBE", child.id, channelId = api.id).channelId)
    }

    @Test
    fun `invalid existing disabled ancestors require explicit correction before admission growth or maintenance`() {
        val fixture = fixture()
        val s = fixture.scenario
        val api = s.registerChannel("API")
        val web = s.registerChannel("WEB", active = false)
        val group = s.channelGroup("GROUP", web.id)
        val root = s.configuration("ROOT", active = false)
        val parent = s.configuration("PARENT", root.id, channelGroupId = group.id, active = false)
        execute(fixture.source, "update limit_configuration set channel_scope = 'CHANNEL', channel_id = ? where id = ?", api.id, root.id)
        val invalidRoot = s.findConfiguration("ROOT")

        for (operation in listOf("creation", "membership", "description", "activation")) {
            val failure = assertFailsWith<ValidationException> {
                when (operation) {
                    "creation" -> s.configuration("CHILD", parent.id, channelGroupId = group.id)
                    "membership" -> s.addChannelMember(group.id, api.id)
                    "description" -> s.facade.tenantCatalogService.updateLimitConfiguration(UpdateLimitConfigurationCommand(
                        s.tenant, parent.id, "Changed", null, BigDecimal.ONE, emptyMap(),
                    ))
                    else -> s.facade.tenantCatalogService.setLimitConfigurationActive(SetLimitConfigurationActiveCommand(s.tenant, parent.id, true))
                }
            }
            assertTrue(failure.message.orEmpty().contains("correct the channel envelope explicitly"))
            assertEquals(invalidRoot, s.findConfiguration("ROOT"))
            assertEquals(parent, s.findConfiguration("PARENT"))
            assertNull(s.findConfiguration("CHILD"))
        }
        execute(fixture.source, "update limit_configuration set channel_scope = 'ALL_CHANNELS', channel_id = null where id = ?", root.id)
        assertEquals(root, s.findConfiguration("ROOT"))
        s.addChannelMember(group.id, api.id)
        assertEquals(api.id, s.configuration("CHILD", parent.id, channelId = api.id).channelId)
    }

    @Test
    fun `disabled PostgreSQL channel envelopes preserve granted rules and allow deactivation and retirement`() {
        val s = fixture().scenario
        val atm = s.registerChannel("ATM")
        val group = s.channelGroup("ATMS", atm.id)
        val root = s.configuration("ROOT", channelGroupId = group.id)
        val child = s.configuration("CHILD", root.id, channelId = atm.id)
        val source = s.source(s.product("ACCOUNT", leaf = true).id)
        val rule = s.grant(child)
        val command = s.requestCommand(child)
        s.facade.tenantCatalogService.setLimitConfigurationActive(SetLimitConfigurationActiveCommand(s.tenant, root.id, false))

        assertFailsWith<ValidationException> { s.facade.limitChangeService.requestLimitRuleChange(command) }
        val allowed = s.authorize(source, "40", atm.id)
        assertEquals(AuthorizationDecision.ALLOWED, allowed.decision)
        assertEquals(listOf(rule.id), allowed.matchedRules.map { it.id })
        assertEquals(AuthorizationDecision.DENIED, s.authorize(source, "501", atm.id).decision)
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
    }

    private fun contend(configurationFirst: Boolean, singleParent: Boolean = false) {
        val fixture = fixture()
        val s = fixture.scenario
        val api = s.registerChannel("API")
        val web = s.registerChannel("WEB", active = false)
        val parentGroup = s.channelGroup("PARENT_GROUP", api.id)
        val childGroup = s.channelGroup("CHILD_GROUP")
        val parent = if (singleParent) s.configuration("PARENT", channelId = api.id, active = false)
            else s.configuration("PARENT", channelGroupId = parentGroup.id, active = false)
        val configuration = s.configurationCommand("CHILD", parent.id, channelGroupId = childGroup.id, active = false)
        val growth = AddChannelGroupMemberCommand(s.tenant, childGroup.id, web.id)
        val snapshotRead = CountDownLatch(1)
        val release = CountDownLatch(1)
        val contenderStarted = CountDownLatch(1)
        val observed = AtomicBoolean()
        val snapshotTable = if (configurationFirst) "channel_group_member" else "limit_configuration"
        val holder = JooqLimitguardFactory(ObservedDataSource(fixture.source, after = { sql ->
            if (isSnapshot(sql, snapshotTable) && observed.compareAndSet(false, true)) {
                snapshotRead.countDown()
                assertTrue(release.await(10, TimeUnit.SECONDS), "Channel envelope barrier timed out")
            }
        })).create(clockProvider = FixedClockProvider(NOW))
        val contender = JooqLimitguardFactory(ObservedDataSource(fixture.source, before = { sql ->
            if (sql.contains("pg_advisory_xact_lock")) contenderStarted.countDown()
        })).create(clockProvider = FixedClockProvider(NOW))
        val executor = Executors.newFixedThreadPool(2)
        fun admit(facade: Limitguard) { facade.tenantCatalogService.createLimitConfiguration(configuration) }
        fun grow(facade: Limitguard) { facade.tenantCatalogService.addChannelGroupMember(growth) }

        try {
            val first = executor.submit<Result<Unit>> {
                runCatching { if (configurationFirst) admit(holder) else grow(holder) }
            }
            assertTrue(snapshotRead.await(10, TimeUnit.SECONDS), "First writer did not read channel coverage")
            val second = executor.submit<Result<Unit>> {
                runCatching { if (configurationFirst) grow(contender) else admit(contender) }
            }
            assertTrue(contenderStarted.await(10, TimeUnit.SECONDS), "Contending writer did not start")
            awaitEnvelopeWriter(fixture.source)
            release.countDown()
            first.get(10, TimeUnit.SECONDS).getOrThrow()
            assertIs<ValidationException>(second.get(10, TimeUnit.SECONDS).exceptionOrNull())

            assertEquals(parent, s.findConfiguration("PARENT"))
            val probe = s.configuration("MEMBERSHIP_PROBE", channelGroupId = childGroup.id)
            if (configurationFirst) {
                val child = requireNotNull(s.findConfiguration("CHILD"))
                assertEquals(parent.id, child.parentConfigurationId)
                assertEquals(childGroup.id, child.channelGroupId)
                assertEquals(false, child.isActive)
                assertFailsWith<ValidationException> { s.configuration("OUTSIDE_PROBE", probe.id, channelId = web.id) }
                assertNull(s.findConfiguration("OUTSIDE_PROBE"))
                assertEquals(api.id, contender.tenantCatalogService.addChannelGroupMember(growth.copy(channelId = api.id)).channelId)
                assertEquals(child.id, s.configuration("INSIDE_PROBE", child.id, channelId = api.id).parentConfigurationId)
            } else {
                assertNull(s.findConfiguration("CHILD"))
                assertEquals(web.id, s.configuration("OUTSIDE_PROBE", probe.id, channelId = web.id).channelId)
                assertEquals(parent.id, contender.tenantCatalogService.createLimitConfiguration(configuration.copy(
                    channelScope = parent.channelScope, channelId = parent.channelId, channelGroupId = parent.channelGroupId,
                )).parentConfigurationId)
            }
        } finally {
            release.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    private fun fixture(): Fixture {
        val schema = "channel_envelope_${UUID.randomUUID().toString().replace("-", "")}"
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
        error("Contending writer did not wait for the channel envelope invariant")
    }

    private data class Fixture(val source: DataSource, val scenario: FundingEnvelopeScenario)

    companion object {
        private val NOW = Instant.parse("2026-10-10T10:00:00Z")

        @Container
        @JvmField
        val postgres = PostgreSQLContainer("postgres:18-alpine")
    }
}
