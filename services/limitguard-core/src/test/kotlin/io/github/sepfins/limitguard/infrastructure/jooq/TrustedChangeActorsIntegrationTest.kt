package io.github.sepfins.limitguard.infrastructure.jooq

import io.github.sepfins.limitguard.api.LimitChangeActorType
import io.github.sepfins.limitguard.api.LimitRuleChangeStatus
import io.github.sepfins.limitguard.api.TrustedChangeActorContext
import io.github.sepfins.limitguard.api.commands.ApplyDueLimitChangesCommand
import io.github.sepfins.limitguard.api.commands.ApproveLimitRuleChangeCommand
import io.github.sepfins.limitguard.api.commands.CancelLimitRuleChangeCommand
import io.github.sepfins.limitguard.api.commands.LimitChangeActorCommand
import io.github.sepfins.limitguard.api.commands.RejectLimitRuleChangeCommand
import io.github.sepfins.limitguard.api.commands.SetChannelActiveCommand
import io.github.sepfins.limitguard.api.commands.SetLimitConfigurationActiveCommand
import io.github.sepfins.limitguard.api.errors.NotFoundException
import io.github.sepfins.limitguard.domain.change.LimitChangePolicy
import io.github.sepfins.limitguard.domain.change.LimitChangePolicyEngine
import io.github.sepfins.limitguard.domain.change.LimitChangePolicyEvaluationOutcome
import io.github.sepfins.limitguard.domain.policy.PolicyEvaluationContext
import io.github.sepfins.limitguard.domain.policy.PolicyEvaluationResult
import io.github.sepfins.limitguard.port.policy.PolicyEngine
import io.github.sepfins.limitguard.support.FixedClockProvider
import io.github.sepfins.limitguard.support.TrustedChangeScenario
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@Testcontainers
class TrustedChangeActorsIntegrationTest {
    private val dataSource by lazy {
        PGSimpleDataSource().apply {
            setURL(postgres.jdbcUrl)
            user = postgres.username
            password = postgres.password
        }
    }

    @Test
    fun `invalid actor and reference commands never reach PostgreSQL foreign keys or policies`() {
        val fixture = fixture()
        val s = fixture.scenario
        val other = TrustedChangeScenario(s.facade, NOW)
        val commands = listOf(
            s.command.copy(requestedBy = s.party(other.owner)),
            s.command.copy(requestChannelId = UUID.randomUUID()),
            s.represented().copy(requesterPartyProfileId = other.owner),
        )
        for (command in commands) {
            assertFailsWith<NotFoundException> { s.facade.limitChangeService.requestLimitRuleChange(command) }
        }
        assertEquals(0, fixture.engine.calls)
        assertEquals(0, count("limit_rule_change_request", s.tenant))
        assertEquals(0, count("limit_rule_change_event", s.tenant))
        assertEquals(0, count("limit_rule_revision", s.tenant))
    }

    @Test
    fun `PostgreSQL withdrawals ignore expired original authority and unavailable policies without mutating rules`() {
        for (cancel in listOf(false, true)) {
            val fixture = fixture()
            val s = fixture.scenario
            val command = s.represented().copy(
                effectiveAt = if (cancel) NOW.plusSeconds(3600) else null,
                target = s.command.target.copy(mandateId = s.mandate),
            )
            val pending = s.facade.limitChangeService.requestLimitRuleChange(command)
            if (cancel) approve(s, pending.id)
            val original = s.details(pending.id).events.first()
            execute("update mandate set is_active = false where tenant_id = ? and id = ?", s.tenant, s.mandate)
            execute("update party_profile_delegation set ends_at = ? where tenant_id = ? and id = ?",
                NOW.plusSeconds(60).atOffset(ZoneOffset.UTC), s.tenant, s.delegation)
            s.facade.tenantCatalogService.setChannelActive(SetChannelActiveCommand(s.tenant, s.channel, false))
            s.facade.tenantCatalogService.setLimitConfigurationActive(SetLimitConfigurationActiveCommand(s.tenant, s.configuration, false))
            fixture.clock.setTo(NOW.plusSeconds(120))
            fixture.engine.calls = 0
            fixture.engine.unavailable = true
            val result = if (cancel) {
                s.facade.limitChangeService.cancelLimitRuleChange(CancelLimitRuleChangeCommand(
                    s.tenant, pending.id, LimitChangeActorCommand(LimitChangeActorType.SYSTEM), "Withdrawn",
                ), TrustedChangeActorContext.InternalSystem)
            } else {
                s.facade.limitChangeService.rejectLimitRuleChange(RejectLimitRuleChangeCommand(
                    s.tenant, pending.id, s.party(s.unrelated), "Declined",
                ))
            }
            assertEquals(if (cancel) LimitRuleChangeStatus.CANCELLED else LimitRuleChangeStatus.REJECTED, result.status)
            assertEquals(0, fixture.engine.calls)
            assertEquals(0, count("limit_rule", s.tenant))
            assertEquals(0, count("limit_rule_revision", s.tenant))
            assertEquals(original, s.details(pending.id).events.first())
            assertEquals(
                if (cancel) LimitChangeActorType.SYSTEM else LimitChangeActorType.PARTY_PROFILE,
                s.details(pending.id).events.last().actor.actorType,
            )
        }
    }

    @Test
    fun `approved scheduled representative intent is applied by SYSTEM`() {
        val fixture = fixture()
        val s = fixture.scenario
        val due = NOW.plusSeconds(60)
        val pending = s.facade.limitChangeService.requestLimitRuleChange(s.represented().copy(effectiveAt = due))
        approve(s, pending.id)
        fixture.clock.setTo(due)
        val result = s.facade.limitChangeService.applyDueLimitChanges(ApplyDueLimitChangesCommand(s.tenant), TrustedChangeActorContext.InternalSystem)
        assertEquals(1, result.appliedCount)
        assertEquals(0, result.failedCount)
        assertEquals(LimitChangeActorType.SYSTEM, s.details(pending.id).revisions.single().actor.actorType)
    }

    private fun fixture(): Fixture {
        val clock = FixedClockProvider(NOW)
        val engine = CountingEngine()
        val facade = JooqLimitguardFactory(dataSource).create(clockProvider = clock, policyEngines = listOf(engine))
        return Fixture(TrustedChangeScenario(facade, NOW), clock, engine)
    }

    private fun approve(s: TrustedChangeScenario, id: UUID) = s.facade.limitChangeService.approveLimitRuleChange(
        ApproveLimitRuleChangeCommand(s.tenant, id, LimitChangeActorCommand(LimitChangeActorType.SYSTEM)), TrustedChangeActorContext.InternalSystem,
    )

    private fun execute(sql: String, vararg values: Any) = dataSource.connection.use { connection ->
        connection.prepareStatement(sql).use { statement ->
            values.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
            statement.executeUpdate()
        }
    }

    private fun count(table: String, tenant: UUID): Int = dataSource.connection.use { connection ->
        connection.prepareStatement("select count(*) from $table where tenant_id = ?").use { statement ->
            statement.setObject(1, tenant)
            statement.executeQuery().use { result ->
                result.next()
                result.getInt(1)
            }
        }
    }

    private data class Fixture(val scenario: TrustedChangeScenario, val clock: FixedClockProvider, val engine: CountingEngine)

    private class CountingEngine : PolicyEngine {
        override val engine = LimitChangePolicyEngine.JSON_LOGIC
        var calls = 0
        var unavailable = false
        override fun evaluate(policy: LimitChangePolicy, context: PolicyEvaluationContext): PolicyEvaluationResult {
            calls++
            check(!unavailable) { "policy facts unavailable" }
            return PolicyEvaluationResult(true, LimitChangePolicyEvaluationOutcome.MATCHED, policy.decision)
        }
    }

    companion object {
        private val NOW = Instant.parse("2026-10-04T12:00:00Z")

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
