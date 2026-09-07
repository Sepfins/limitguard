package io.github.sepfins.limitguard.infrastructure.jooq.repository

import io.github.sepfins.limitguard.domain.change.LimitChangePolicy
import io.github.sepfins.limitguard.domain.change.LimitChangePolicyCode
import io.github.sepfins.limitguard.domain.change.LimitChangePolicyDecision
import io.github.sepfins.limitguard.domain.change.LimitChangePolicyEngine
import io.github.sepfins.limitguard.domain.change.LimitChangePolicyEvaluation
import io.github.sepfins.limitguard.domain.change.LimitChangePolicyEvaluationOutcome
import io.github.sepfins.limitguard.domain.change.LimitChangePolicyName
import io.github.sepfins.limitguard.domain.change.LimitChangePolicyPhase
import io.github.sepfins.limitguard.domain.common.EffectiveWindow
import io.github.sepfins.limitguard.domain.common.LimitChangePolicyEvaluationId
import io.github.sepfins.limitguard.domain.common.LimitChangePolicyId
import io.github.sepfins.limitguard.domain.common.LimitChangeRequestId
import io.github.sepfins.limitguard.domain.common.LimitConfigurationId
import io.github.sepfins.limitguard.domain.common.TenantId
import io.github.sepfins.limitguard.domain.limit.LimitPriority
import io.github.sepfins.limitguard.infrastructure.jooq.JooqPersistenceContext
import io.github.sepfins.limitguard.infrastructure.jooq.eqPostgresEnum
import io.github.sepfins.limitguard.infrastructure.jooq.jooqField
import io.github.sepfins.limitguard.infrastructure.jooq.jooqOperation
import io.github.sepfins.limitguard.infrastructure.jooq.jooqTable
import io.github.sepfins.limitguard.infrastructure.jooq.postgresEnum
import io.github.sepfins.limitguard.infrastructure.jooq.toJsonb
import io.github.sepfins.limitguard.infrastructure.jooq.toMetadata
import io.github.sepfins.limitguard.infrastructure.jooq.toObjectList
import io.github.sepfins.limitguard.port.persistence.LimitChangePolicyEvaluationRepository
import io.github.sepfins.limitguard.port.persistence.LimitChangePolicyRepository
import org.jooq.Field
import org.jooq.JSONB
import org.jooq.Record
import org.jooq.SelectFieldOrAsterisk
import java.time.Instant
import java.util.UUID

class JooqLimitChangePolicyRepository(
    private val context: JooqPersistenceContext,
) : LimitChangePolicyRepository {
    override fun findById(tenantId: TenantId, id: LimitChangePolicyId): LimitChangePolicy? =
        jooqOperation("find limit change policy by id") {
            selectPolicies()
                .where(LimitChangePolicyTable.tenantId.eq(tenantId.value))
                .and(LimitChangePolicyTable.id.eq(id.value))
                .fetchOne { it.toPolicy() }
        }

    override fun findByCode(tenantId: TenantId, code: LimitChangePolicyCode): LimitChangePolicy? =
        jooqOperation("find limit change policy by code") {
            selectPolicies()
                .where(LimitChangePolicyTable.tenantId.eq(tenantId.value))
                .and(LimitChangePolicyTable.code.eq(code.value))
                .fetchOne { it.toPolicy() }
        }

    override fun findActiveByPhase(
        tenantId: TenantId,
        phase: LimitChangePolicyPhase,
        at: Instant,
    ): List<LimitChangePolicy> = jooqOperation("find active limit change policies by phase") {
        selectPolicies()
            .where(LimitChangePolicyTable.tenantId.eq(tenantId.value))
            .and(LimitChangePolicyTable.phase.eqPostgresEnum(phase.name, "limit_change_policy_phase"))
            .and(LimitChangePolicyTable.isActive.isTrue)
            .and(LimitChangePolicyTable.startsAt.le(at))
            .and(LimitChangePolicyTable.endsAt.isNull.or(LimitChangePolicyTable.endsAt.gt(at)))
            .orderBy(LimitChangePolicyTable.priority, LimitChangePolicyTable.code, LimitChangePolicyTable.id)
            .fetch { it.toPolicy() }
    }

    override fun insert(tenantId: TenantId, policy: LimitChangePolicy): LimitChangePolicy =
        jooqOperation("insert limit change policy") {
            require(policy.tenantId == tenantId) { "limit change policy tenant does not match repository tenant" }
            val insert = context.dsl()
                .insertInto(LimitChangePolicyTable.table)
                .set(LimitChangePolicyTable.id, policy.id.value)
                .set(LimitChangePolicyTable.tenantId, tenantId.value)
                .set(LimitChangePolicyTable.code, policy.code.value)
                .set(LimitChangePolicyTable.name, policy.name.value)
                .set(LimitChangePolicyTable.description, policy.description)
                .set(LimitChangePolicyTable.phase, postgresEnum(policy.phase.name, "limit_change_policy_phase"))
                .set(LimitChangePolicyTable.engine, postgresEnum(policy.engine.name, "limit_change_policy_engine"))
                .set(
                    LimitChangePolicyTable.decision,
                    postgresEnum(policy.decision.name, "limit_change_policy_decision"),
                )
                .set(LimitChangePolicyTable.limitConfigurationId, policy.limitConfigurationId?.value)
                .set(LimitChangePolicyTable.priority, policy.priority.value)
                .set(LimitChangePolicyTable.isActive, policy.isActive)
                .set(LimitChangePolicyTable.appliesTo, policy.appliesTo.toJsonb())
                .set(LimitChangePolicyTable.conditionExpression, policy.conditionExpression.toJsonb())
                .set(LimitChangePolicyTable.actionExpression, policy.actionExpression.toJsonb())
                .set(LimitChangePolicyTable.denialCode, policy.denialCode)
                .set(LimitChangePolicyTable.denialMessage, policy.denialMessage)
                .set(LimitChangePolicyTable.startsAt, policy.effectiveWindow.startsAt)
                .set(LimitChangePolicyTable.endsAt, policy.effectiveWindow.endsAt)
                .set(LimitChangePolicyTable.metadata, policy.metadata.toJsonb())
            policy.createdAt?.let { insert.set(LimitChangePolicyTable.createdAt, it) }
            policy.updatedAt?.let { insert.set(LimitChangePolicyTable.updatedAt, it) }
            requireNotNull(insert.returning(LimitChangePolicyTable.fields).fetchOne()).toPolicy()
        }

    override fun update(tenantId: TenantId, policy: LimitChangePolicy): LimitChangePolicy =
        jooqOperation("update limit change policy") {
            require(policy.tenantId == tenantId) { "limit change policy tenant does not match repository tenant" }
            val update = context.dsl()
                .update(LimitChangePolicyTable.table)
                .set(LimitChangePolicyTable.code, policy.code.value)
                .set(LimitChangePolicyTable.name, policy.name.value)
                .set(LimitChangePolicyTable.description, policy.description)
                .set(LimitChangePolicyTable.phase, postgresEnum(policy.phase.name, "limit_change_policy_phase"))
                .set(LimitChangePolicyTable.engine, postgresEnum(policy.engine.name, "limit_change_policy_engine"))
                .set(
                    LimitChangePolicyTable.decision,
                    postgresEnum(policy.decision.name, "limit_change_policy_decision"),
                )
                .set(LimitChangePolicyTable.limitConfigurationId, policy.limitConfigurationId?.value)
                .set(LimitChangePolicyTable.priority, policy.priority.value)
                .set(LimitChangePolicyTable.isActive, policy.isActive)
                .set(LimitChangePolicyTable.appliesTo, policy.appliesTo.toJsonb())
                .set(LimitChangePolicyTable.conditionExpression, policy.conditionExpression.toJsonb())
                .set(LimitChangePolicyTable.actionExpression, policy.actionExpression.toJsonb())
                .set(LimitChangePolicyTable.denialCode, policy.denialCode)
                .set(LimitChangePolicyTable.denialMessage, policy.denialMessage)
                .set(LimitChangePolicyTable.startsAt, policy.effectiveWindow.startsAt)
                .set(LimitChangePolicyTable.endsAt, policy.effectiveWindow.endsAt)
                .set(LimitChangePolicyTable.metadata, policy.metadata.toJsonb())
            policy.updatedAt?.let { update.set(LimitChangePolicyTable.updatedAt, it) }
            requireNotNull(
                update.where(LimitChangePolicyTable.tenantId.eq(tenantId.value))
                    .and(LimitChangePolicyTable.id.eq(policy.id.value))
                    .returning(LimitChangePolicyTable.fields)
                    .fetchOne()
            ) { "limit change policy not found: ${policy.id.value}" }.toPolicy()
        }

    private fun selectPolicies() = context.dsl()
        .select(LimitChangePolicyTable.fields)
        .from(LimitChangePolicyTable.table)

    private fun Record.toPolicy(): LimitChangePolicy = LimitChangePolicy(
        id = LimitChangePolicyId.of(requireNotNull(get(LimitChangePolicyTable.id))),
        tenantId = TenantId.of(requireNotNull(get(LimitChangePolicyTable.tenantId))),
        code = LimitChangePolicyCode.of(requireNotNull(get(LimitChangePolicyTable.code))),
        name = LimitChangePolicyName.of(requireNotNull(get(LimitChangePolicyTable.name))),
        phase = LimitChangePolicyPhase.valueOf(requireNotNull(get(LimitChangePolicyTable.phase))),
        effectiveWindow = EffectiveWindow(
            startsAt = requireNotNull(get(LimitChangePolicyTable.startsAt)),
            endsAt = get(LimitChangePolicyTable.endsAt),
        ),
        description = get(LimitChangePolicyTable.description),
        engine = LimitChangePolicyEngine.valueOf(requireNotNull(get(LimitChangePolicyTable.engine))),
        decision = LimitChangePolicyDecision.valueOf(requireNotNull(get(LimitChangePolicyTable.decision))),
        limitConfigurationId = get(LimitChangePolicyTable.limitConfigurationId)?.let(LimitConfigurationId::of),
        priority = LimitPriority.of(requireNotNull(get(LimitChangePolicyTable.priority))),
        isActive = requireNotNull(get(LimitChangePolicyTable.isActive)),
        appliesTo = get(LimitChangePolicyTable.appliesTo).toMetadata(),
        conditionExpression = get(LimitChangePolicyTable.conditionExpression).toMetadata(),
        actionExpression = get(LimitChangePolicyTable.actionExpression).toObjectList("policy action expression"),
        denialCode = get(LimitChangePolicyTable.denialCode),
        denialMessage = get(LimitChangePolicyTable.denialMessage),
        metadata = get(LimitChangePolicyTable.metadata).toMetadata(),
        createdAt = get(LimitChangePolicyTable.createdAt),
        updatedAt = get(LimitChangePolicyTable.updatedAt),
    )
}

class JooqLimitChangePolicyEvaluationRepository(
    private val context: JooqPersistenceContext,
) : LimitChangePolicyEvaluationRepository {
    override fun findById(
        tenantId: TenantId,
        id: LimitChangePolicyEvaluationId,
    ): LimitChangePolicyEvaluation? = jooqOperation("find policy evaluation by id") {
        selectEvaluations()
            .where(LimitChangePolicyEvaluationTable.tenantId.eq(tenantId.value))
            .and(LimitChangePolicyEvaluationTable.id.eq(id.value))
            .fetchOne { it.toEvaluation() }
    }

    override fun findByChangeRequest(
        tenantId: TenantId,
        changeRequestId: LimitChangeRequestId,
    ): List<LimitChangePolicyEvaluation> = jooqOperation("find policy evaluations by change request") {
        selectEvaluations()
            .where(LimitChangePolicyEvaluationTable.tenantId.eq(tenantId.value))
            .and(LimitChangePolicyEvaluationTable.changeRequestId.eq(changeRequestId.value))
            .orderBy(LimitChangePolicyEvaluationTable.evaluatedAt, LimitChangePolicyEvaluationTable.id)
            .fetch { it.toEvaluation() }
    }

    override fun insert(
        tenantId: TenantId,
        evaluation: LimitChangePolicyEvaluation,
    ): LimitChangePolicyEvaluation = jooqOperation("insert policy evaluation") {
        require(evaluation.tenantId == tenantId) { "policy evaluation tenant does not match repository tenant" }
        val insert = context.dsl()
            .insertInto(LimitChangePolicyEvaluationTable.table)
            .set(LimitChangePolicyEvaluationTable.id, evaluation.id.value)
            .set(LimitChangePolicyEvaluationTable.tenantId, tenantId.value)
            .set(LimitChangePolicyEvaluationTable.changeRequestId, evaluation.changeRequestId.value)
            .set(LimitChangePolicyEvaluationTable.policyId, evaluation.policyId.value)
            .set(
                LimitChangePolicyEvaluationTable.phase,
                postgresEnum(evaluation.phase.name, "limit_change_policy_phase"),
            )
            .set(
                LimitChangePolicyEvaluationTable.outcome,
                postgresEnum(evaluation.outcome.name, "limit_change_policy_evaluation_outcome"),
            )
            .set(
                LimitChangePolicyEvaluationTable.decision,
                postgresEnum(evaluation.decision.name, "limit_change_policy_decision"),
            )
            .set(LimitChangePolicyEvaluationTable.matched, evaluation.matched)
            .set(LimitChangePolicyEvaluationTable.policySnapshot, evaluation.policySnapshot.toJsonb())
            .set(LimitChangePolicyEvaluationTable.inputContext, evaluation.inputContext.toJsonb())
            .set(LimitChangePolicyEvaluationTable.actionResult, evaluation.actionResult.toJsonb())
            .set(LimitChangePolicyEvaluationTable.message, evaluation.message)
        evaluation.evaluatedAt?.let { insert.set(LimitChangePolicyEvaluationTable.evaluatedAt, it) }
        requireNotNull(insert.returning(LimitChangePolicyEvaluationTable.fields).fetchOne()).toEvaluation()
    }

    private fun selectEvaluations() = context.dsl()
        .select(LimitChangePolicyEvaluationTable.fields)
        .from(LimitChangePolicyEvaluationTable.table)

    private fun Record.toEvaluation(): LimitChangePolicyEvaluation = LimitChangePolicyEvaluation(
        id = LimitChangePolicyEvaluationId.of(requireNotNull(get(LimitChangePolicyEvaluationTable.id))),
        tenantId = TenantId.of(requireNotNull(get(LimitChangePolicyEvaluationTable.tenantId))),
        changeRequestId = LimitChangeRequestId.of(
            requireNotNull(get(LimitChangePolicyEvaluationTable.changeRequestId))
        ),
        policyId = LimitChangePolicyId.of(requireNotNull(get(LimitChangePolicyEvaluationTable.policyId))),
        phase = LimitChangePolicyPhase.valueOf(requireNotNull(get(LimitChangePolicyEvaluationTable.phase))),
        outcome = LimitChangePolicyEvaluationOutcome.valueOf(
            requireNotNull(get(LimitChangePolicyEvaluationTable.outcome))
        ),
        decision = LimitChangePolicyDecision.valueOf(
            requireNotNull(get(LimitChangePolicyEvaluationTable.decision))
        ),
        matched = requireNotNull(get(LimitChangePolicyEvaluationTable.matched)),
        policySnapshot = get(LimitChangePolicyEvaluationTable.policySnapshot).toMetadata(),
        inputContext = get(LimitChangePolicyEvaluationTable.inputContext).toMetadata(),
        actionResult = get(LimitChangePolicyEvaluationTable.actionResult).toMetadata(),
        message = get(LimitChangePolicyEvaluationTable.message),
        evaluatedAt = get(LimitChangePolicyEvaluationTable.evaluatedAt),
    )
}

private object LimitChangePolicyTable {
    const val NAME = "limit_change_policy"
    val table = jooqTable(NAME)
    val id: Field<UUID> = jooqField(NAME, "id", UUID::class.java)
    val tenantId: Field<UUID> = jooqField(NAME, "tenant_id", UUID::class.java)
    val code: Field<String> = jooqField(NAME, "code", String::class.java)
    val name: Field<String> = jooqField(NAME, "name", String::class.java)
    val description: Field<String> = jooqField(NAME, "description", String::class.java)
    val phase: Field<String> = jooqField(NAME, "phase", String::class.java)
    val engine: Field<String> = jooqField(NAME, "engine", String::class.java)
    val decision: Field<String> = jooqField(NAME, "decision", String::class.java)
    val limitConfigurationId: Field<UUID> = jooqField(NAME, "limit_configuration_id", UUID::class.java)
    val priority: Field<Int> = jooqField(NAME, "priority", Int::class.java)
    val isActive: Field<Boolean> = jooqField(NAME, "is_active", Boolean::class.java)
    val appliesTo: Field<JSONB> = jooqField(NAME, "applies_to", JSONB::class.java)
    val conditionExpression: Field<JSONB> = jooqField(NAME, "condition_expression", JSONB::class.java)
    val actionExpression: Field<JSONB> = jooqField(NAME, "action_expression", JSONB::class.java)
    val denialCode: Field<String> = jooqField(NAME, "denial_code", String::class.java)
    val denialMessage: Field<String> = jooqField(NAME, "denial_message", String::class.java)
    val startsAt: Field<Instant> = jooqField(NAME, "starts_at", Instant::class.java)
    val endsAt: Field<Instant> = jooqField(NAME, "ends_at", Instant::class.java)
    val metadata: Field<JSONB> = jooqField(NAME, "metadata", JSONB::class.java)
    val createdAt: Field<Instant> = jooqField(NAME, "created_at", Instant::class.java)
    val updatedAt: Field<Instant> = jooqField(NAME, "updated_at", Instant::class.java)
    val fields: List<SelectFieldOrAsterisk> = listOf(
        id,
        tenantId,
        code,
        name,
        description,
        phase,
        engine,
        decision,
        limitConfigurationId,
        priority,
        isActive,
        appliesTo,
        conditionExpression,
        actionExpression,
        denialCode,
        denialMessage,
        startsAt,
        endsAt,
        metadata,
        createdAt,
        updatedAt,
    )
}

private object LimitChangePolicyEvaluationTable {
    const val NAME = "limit_change_policy_evaluation"
    val table = jooqTable(NAME)
    val id: Field<UUID> = jooqField(NAME, "id", UUID::class.java)
    val tenantId: Field<UUID> = jooqField(NAME, "tenant_id", UUID::class.java)
    val changeRequestId: Field<UUID> = jooqField(NAME, "change_request_id", UUID::class.java)
    val policyId: Field<UUID> = jooqField(NAME, "policy_id", UUID::class.java)
    val phase: Field<String> = jooqField(NAME, "phase", String::class.java)
    val outcome: Field<String> = jooqField(NAME, "outcome", String::class.java)
    val decision: Field<String> = jooqField(NAME, "decision", String::class.java)
    val matched: Field<Boolean> = jooqField(NAME, "matched", Boolean::class.java)
    val policySnapshot: Field<JSONB> = jooqField(NAME, "policy_snapshot", JSONB::class.java)
    val inputContext: Field<JSONB> = jooqField(NAME, "input_context", JSONB::class.java)
    val actionResult: Field<JSONB> = jooqField(NAME, "action_result", JSONB::class.java)
    val message: Field<String> = jooqField(NAME, "message", String::class.java)
    val evaluatedAt: Field<Instant> = jooqField(NAME, "evaluated_at", Instant::class.java)
    val fields: List<SelectFieldOrAsterisk> = listOf(
        id,
        tenantId,
        changeRequestId,
        policyId,
        phase,
        outcome,
        decision,
        matched,
        policySnapshot,
        inputContext,
        actionResult,
        message,
        evaluatedAt,
    )
}
