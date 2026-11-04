package io.github.sepfins.limitguard.infrastructure.jooq

import io.github.sepfins.limitguard.api.LimitAmount
import io.github.sepfins.limitguard.api.TrustedChangeActorContext
import io.github.sepfins.limitguard.api.LimitChangeActorType
import io.github.sepfins.limitguard.api.LimitKind
import io.github.sepfins.limitguard.api.PartyType
import io.github.sepfins.limitguard.api.commands.ApproveLimitRuleChangeCommand
import io.github.sepfins.limitguard.api.commands.CreateLimitConfigurationCommand
import io.github.sepfins.limitguard.api.commands.CreateLimitChangePolicyCommand
import io.github.sepfins.limitguard.api.commands.LimitChangeActorCommand
import io.github.sepfins.limitguard.api.commands.LimitRuleChangeOperation
import io.github.sepfins.limitguard.api.commands.LimitRuleTargetCommand
import io.github.sepfins.limitguard.api.commands.RegisterPartyProfileCommand
import io.github.sepfins.limitguard.api.commands.RegisterTenantCommand
import io.github.sepfins.limitguard.api.commands.RequestLimitRuleChangeCommand
import io.github.sepfins.limitguard.api.errors.IdempotencyConflictException
import io.github.sepfins.limitguard.api.errors.ValidationException
import io.github.sepfins.limitguard.api.queries.GetLimitChangeQuery
import io.github.sepfins.limitguard.domain.change.LimitChangePolicy
import io.github.sepfins.limitguard.domain.change.LimitChangePolicyCode
import io.github.sepfins.limitguard.domain.change.LimitChangePolicyName
import io.github.sepfins.limitguard.domain.change.LimitChangePolicyPhase
import io.github.sepfins.limitguard.domain.change.LimitChangePolicyEngine
import io.github.sepfins.limitguard.domain.change.LimitChangePolicyEvaluationOutcome
import io.github.sepfins.limitguard.domain.common.EffectiveWindow
import io.github.sepfins.limitguard.domain.common.LimitChangePolicyId
import io.github.sepfins.limitguard.domain.common.LimitChangeRequestId
import io.github.sepfins.limitguard.domain.common.TenantId
import io.github.sepfins.limitguard.domain.common.Metadata
import io.github.sepfins.limitguard.domain.policy.PolicyEvaluationContext
import io.github.sepfins.limitguard.domain.policy.PolicyEvaluationResult
import io.github.sepfins.limitguard.port.policy.PolicyEngine
import io.github.sepfins.limitguard.infrastructure.jooq.repository.JooqLimitChangePolicyRepository
import io.github.sepfins.limitguard.infrastructure.jooq.repository.JooqLimitChangePolicyEvaluationRepository
import io.github.sepfins.limitguard.infrastructure.jooq.repository.JooqLimitChangeRequestRepository
import io.github.sepfins.limitguard.support.FixedClockProvider
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import java.math.BigDecimal
import java.math.BigInteger
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@Testcontainers
class ImmutableJsonPersistenceIntegrationTest {
    private val dataSource by lazy {
        PGSimpleDataSource().apply {
            setURL(postgres.jdbcUrl)
            user = postgres.username
            password = postgres.password
        }
    }
    private val facade by lazy { JooqLimitguardFactory(dataSource).create(clockProvider = FixedClockProvider(NOW)) }
    private val tenant by lazy {
        facade.tenantCatalogService.registerTenant(RegisterTenantCommand("JSON bank ${UUID.randomUUID()}", ZoneOffset.UTC)).id
    }

    @Test
    fun `malformed engine JSON rolls back admission and leaves the creation key reusable`() {
        var malformed = true
        val engine = object : PolicyEngine {
            override val engine = LimitChangePolicyEngine.JSON_LOGIC
            override fun evaluate(policy: LimitChangePolicy, context: PolicyEvaluationContext): PolicyEvaluationResult {
                val output = mutableMapOf<String, Any?>()
                if (malformed) output["self"] = output
                return PolicyEvaluationResult(
                    matched = true, outcome = LimitChangePolicyEvaluationOutcome.MATCHED,
                    decision = policy.decision, actionResult = Metadata(output),
                )
            }
        }
        val guarded = JooqLimitguardFactory(dataSource).create(
            clockProvider = FixedClockProvider(NOW), policyEngines = listOf(engine),
        )
        guarded.tenantCatalogService.createLimitChangePolicy(CreateLimitChangePolicyCommand(
            tenantId = tenant, code = "ENGINE", name = "Engine", phase = io.github.sepfins.limitguard.api.LimitChangePolicyPhase.AUTHORIZATION,
            decision = io.github.sepfins.limitguard.api.LimitChangePolicyDecision.ALLOW,
        ))
        val command = requestCommand(emptyMap())
        assertFailsWith<ValidationException> { guarded.limitChangeService.requestLimitRuleChange(command, trustedActorContext = TrustedChangeActorContext.InternalSystem) }
        malformed = false
        val result = guarded.limitChangeService.requestLimitRuleChange(command, trustedActorContext = TrustedChangeActorContext.InternalSystem)
        assertEquals(io.github.sepfins.limitguard.api.LimitRuleChangeStatus.APPLIED, result.status)
        assertEquals(result, guarded.limitChangeService.requestLimitRuleChange(command, trustedActorContext = TrustedChangeActorContext.InternalSystem))
        val events = guarded.limitInquiryService.getLimitChange(GetLimitChangeQuery(tenant, result.id)).events
        assertEquals(listOf("REQUESTED", "POLICY_EVALUATED", "APPROVED", "APPLIED"), events.map { it.eventType.name })
    }

    @Test
    fun `original creation intent retains exact numbers nulls and normalized replay through postgres`() {
        val large = "1234567890123456789012345678901234567890"
        val decimal = "0.123456789012345678901234567890"
        val metadata = mapOf(
            "large" to BigInteger(large), "decimal" to BigDecimal(decimal), "count" to 1L,
            "optional" to null, "nested" to mapOf("items" to listOf(null, false, BigInteger(large))),
        )
        val explanation = mutableListOf<Any?>("original explanation", null)
        var engineInputMutationBlocked = false
        val engine = object : PolicyEngine {
            override val engine = LimitChangePolicyEngine.JSON_LOGIC
            override fun evaluate(policy: LimitChangePolicy, context: PolicyEvaluationContext): PolicyEvaluationResult {
                engineInputMutationBlocked = runCatching {
                    ((context.facts.value["nested"] as Map<*, *>)["items"] as MutableList<*>).clear()
                }.exceptionOrNull() is UnsupportedOperationException
                return PolicyEvaluationResult(
                    matched = true, outcome = LimitChangePolicyEvaluationOutcome.MATCHED, decision = policy.decision,
                    actionResult = Metadata(mapOf("explanation" to explanation, "exact" to metadata)),
                )
            }
        }
        val facade = JooqLimitguardFactory(dataSource).create(
            clockProvider = FixedClockProvider(NOW), policyEngines = listOf(engine),
        )
        facade.tenantCatalogService.createLimitChangePolicy(CreateLimitChangePolicyCommand(
            tenantId = tenant, code = "EXACT", name = "Exact evidence", phase = io.github.sepfins.limitguard.api.LimitChangePolicyPhase.AUTHORIZATION,
            decision = io.github.sepfins.limitguard.api.LimitChangePolicyDecision.REQUIRE_APPROVAL, metadata = metadata,
        ))
        val command = requestCommand(metadata)
        val pending = facade.limitChangeService.requestLimitRuleChange(command, trustedActorContext = TrustedChangeActorContext.InternalSystem)
        explanation[0] = "changed explanation"
        assertTrue(engineInputMutationBlocked)
        val normalized = metadata + mapOf("large" to BigDecimal("$large.0"), "count" to BigDecimal("1.000"))
        val retry = command.copy(
            metadata = normalized, policyContext = normalized,
            operation = LimitRuleChangeOperation.SetAmount(LimitAmount.Bounded(BigDecimal("500.000010"))),
        )
        assertEquals(pending, facade.limitChangeService.requestLimitRuleChange(retry, trustedActorContext = TrustedChangeActorContext.InternalSystem))
        val persisted = assertNotNull(JooqLimitChangeRequestRepository(JooqPersistenceContext(dataSource)).findById(
            TenantId.of(tenant), LimitChangeRequestId.of(pending.id),
        ))
        assertEquals(BigDecimal(large), persisted.metadata.value["large"])
        assertEquals(BigDecimal(decimal), persisted.metadata.value["decimal"])
        assertTrue(persisted.metadata.value.containsKey("optional"))
        assertEquals(null, persisted.metadata.value["optional"])
        val items = (persisted.metadata.value["nested"] as Map<*, *>)["items"] as List<*>
        assertEquals(listOf(null, false, BigDecimal(large)), items)
        assertFailsWith<UnsupportedOperationException> { (items as MutableList<*>).clear() }

        val applied = facade.limitChangeService.approveLimitRuleChange(ApproveLimitRuleChangeCommand(
            tenant, pending.id, LimitChangeActorCommand(LimitChangeActorType.SYSTEM),
        ),
            trustedActorContext = TrustedChangeActorContext.InternalSystem,
        )
        val details = facade.limitInquiryService.getLimitChange(GetLimitChangeQuery(tenant, pending.id))
        val intent = details.events.first().payload["originalIntent"] as Map<*, *>
        val submission = intent["submission"] as Map<*, *>
        assertEquals(BigDecimal(decimal), (submission["policyContext"] as Map<*, *>)["decimal"])
        assertEquals("500.00001", (submission["operation"] as Map<*, *>)["amount"])
        val evaluation = JooqLimitChangePolicyEvaluationRepository(JooqPersistenceContext(dataSource))
            .findByChangeRequest(TenantId.of(tenant), LimitChangeRequestId.of(pending.id)).single()
        assertEquals(BigDecimal(decimal), (evaluation.inputContext.value["facts"] as Map<*, *>)["decimal"])
        assertEquals(BigDecimal(large), (evaluation.policySnapshot.value["metadata"] as Map<*, *>)["large"])
        assertEquals(listOf("original explanation", null), evaluation.actionResult.value["explanation"])
        assertFailsWith<UnsupportedOperationException> {
            (evaluation.actionResult.value["explanation"] as MutableList<*>).clear()
        }
        assertEquals(applied, facade.limitChangeService.requestLimitRuleChange(retry, trustedActorContext = TrustedChangeActorContext.InternalSystem))
        for (different in listOf(metadata - "optional", metadata + ("large" to BigInteger(large).add(BigInteger.ONE)))) {
            assertFailsWith<IdempotencyConflictException> {
                facade.limitChangeService.requestLimitRuleChange(command.copy(metadata = different), trustedActorContext = TrustedChangeActorContext.InternalSystem)
            }
        }
        assertEquals(details, facade.limitInquiryService.getLimitChange(GetLimitChangeQuery(tenant, pending.id)))
        assertEquals(evaluation, JooqLimitChangePolicyEvaluationRepository(JooqPersistenceContext(dataSource))
            .findByChangeRequest(TenantId.of(tenant), LimitChangeRequestId.of(pending.id)).single())
    }

    @Test
    fun `policy persistence validates JSON and returns recursively immutable actions`() {
        val repository = JooqLimitChangePolicyRepository(JooqPersistenceContext(dataSource))
        val policy = LimitChangePolicy(
            id = LimitChangePolicyId.of(UUID.randomUUID()), tenantId = TenantId.of(tenant),
            code = LimitChangePolicyCode.of("CAP"), name = LimitChangePolicyName.of("Cap"),
            phase = LimitChangePolicyPhase.AUTHORIZATION, effectiveWindow = EffectiveWindow(NOW, null),
            actionExpression = listOf(mapOf("type" to "CAP_REQUESTED_AMOUNT", "amount" to mapOf("var" to "facts.cap"))),
        )
        val invalid = policy.copy(actionExpression = listOf(mapOf("bad" to mapOf(1 to "non-string key"))))
        assertFailsWith<ValidationException> { repository.insert(policy.tenantId, invalid) }
        val stored = repository.insert(policy.tenantId, policy)
        assertEquals(policy.actionExpression, stored.actionExpression)
        assertFailsWith<UnsupportedOperationException> { (stored.actionExpression as MutableList<*>).clear() }
        assertFailsWith<UnsupportedOperationException> { (stored.actionExpression[0]["amount"] as MutableMap<*, *>).clear() }
        assertEquals(stored, repository.findById(policy.tenantId, policy.id))
    }

    private fun requestCommand(metadata: Map<String, Any?>): RequestLimitRuleChangeCommand {
        val owner = facade.tenantCatalogService.registerPartyProfile(RegisterPartyProfileCommand(
            tenantId = tenant, partyType = PartyType.INDIVIDUAL, firstName = "Alex", lastName = "Customer",
        )).id
        val configuration = facade.tenantCatalogService.createLimitConfiguration(CreateLimitConfigurationCommand(
            tenantId = tenant, code = "GENERAL", name = "General limit", limitKind = LimitKind.PER_TRANSACTION,
            currency = "EUR", maxAssignableAmount = LimitAmount.Bounded(BigDecimal("1000")),
        )).id
        return RequestLimitRuleChangeCommand(
            tenantId = tenant, requestedBy = LimitChangeActorCommand(LimitChangeActorType.SYSTEM),
            target = LimitRuleTargetCommand(owner, configuration),
            operation = LimitRuleChangeOperation.SetAmount(LimitAmount.Bounded(BigDecimal("500.00001"))),
            policyContext = metadata, metadata = metadata, idempotencyKey = "exact-json",
        )
    }

    companion object {
        private val NOW = Instant.parse("2026-06-26T12:00:00Z")

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
