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
import io.github.sepfins.limitguard.infrastructure.jooq.jooqOperation
import io.github.sepfins.limitguard.infrastructure.jooq.toJsonb
import io.github.sepfins.limitguard.infrastructure.jooq.toMetadata
import io.github.sepfins.limitguard.infrastructure.jooq.toObjectList
import io.github.sepfins.limitguard.infrastructure.jooq.generated.enums.LimitChangePolicyDecision as GeneratedLimitChangePolicyDecision
import io.github.sepfins.limitguard.infrastructure.jooq.generated.enums.LimitChangePolicyEngine as GeneratedLimitChangePolicyEngine
import io.github.sepfins.limitguard.infrastructure.jooq.generated.enums.LimitChangePolicyEvaluationOutcome as GeneratedLimitChangePolicyEvaluationOutcome
import io.github.sepfins.limitguard.infrastructure.jooq.generated.enums.LimitChangePolicyPhase as GeneratedLimitChangePolicyPhase
import io.github.sepfins.limitguard.infrastructure.jooq.generated.tables.LimitChangePolicy as GeneratedLimitChangePolicyTable
import io.github.sepfins.limitguard.infrastructure.jooq.generated.tables.LimitChangePolicyEvaluation as GeneratedLimitChangePolicyEvaluationTable
import io.github.sepfins.limitguard.port.persistence.LimitChangePolicyEvaluationRepository
import io.github.sepfins.limitguard.port.persistence.LimitChangePolicyRepository
import org.jooq.Record
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

class JooqLimitChangePolicyRepository(
    private val context: JooqPersistenceContext,
) : LimitChangePolicyRepository {
    private val table = GeneratedLimitChangePolicyTable.LIMIT_CHANGE_POLICY

    override fun findById(tenantId: TenantId, id: LimitChangePolicyId): LimitChangePolicy? =
        jooqOperation("find limit change policy by id") {
            selectPolicies()
                .where(table.TENANT_ID.eq(tenantId.value))
                .and(table.ID.eq(id.value))
                .fetchOne { it.toPolicy() }
        }

    override fun findByCode(tenantId: TenantId, code: LimitChangePolicyCode): LimitChangePolicy? =
        jooqOperation("find limit change policy by code") {
            selectPolicies()
                .where(table.TENANT_ID.eq(tenantId.value))
                .and(table.CODE.eq(code.value))
                .fetchOne { it.toPolicy() }
        }

    override fun findActiveByPhase(
        tenantId: TenantId,
        phase: LimitChangePolicyPhase,
        at: Instant,
    ): List<LimitChangePolicy> = jooqOperation("find active limit change policies by phase") {
        val timestamp = at.toOffsetDateTime()
        selectPolicies()
            .where(table.TENANT_ID.eq(tenantId.value))
            .and(table.PHASE.eq(phase.toGenerated()))
            .and(table.IS_ACTIVE.isTrue)
            .and(table.STARTS_AT.le(timestamp))
            .and(table.ENDS_AT.isNull.or(table.ENDS_AT.gt(timestamp)))
            .orderBy(table.PRIORITY, table.CODE, table.ID)
            .fetch { it.toPolicy() }
    }

    override fun insert(tenantId: TenantId, policy: LimitChangePolicy): LimitChangePolicy =
        jooqOperation("insert limit change policy") {
            require(policy.tenantId == tenantId) { "limit change policy tenant does not match repository tenant" }
            val insert = context.dsl()
                .insertInto(table)
                .set(table.ID, policy.id.value)
                .set(table.TENANT_ID, tenantId.value)
                .set(table.CODE, policy.code.value)
                .set(table.NAME, policy.name.value)
                .set(table.DESCRIPTION, policy.description)
                .set(table.PHASE, policy.phase.toGenerated())
                .set(table.ENGINE, policy.engine.toGenerated())
                .set(table.DECISION, policy.decision.toGenerated())
                .set(table.LIMIT_CONFIGURATION_ID, policy.limitConfigurationId?.value)
                .set(table.PRIORITY, policy.priority.value)
                .set(table.IS_ACTIVE, policy.isActive)
                .set(table.APPLIES_TO, policy.appliesTo.toJsonb())
                .set(table.CONDITION_EXPRESSION, policy.conditionExpression.toJsonb())
                .set(table.ACTION_EXPRESSION, policy.actionExpression.toJsonb())
                .set(table.DENIAL_CODE, policy.denialCode)
                .set(table.DENIAL_MESSAGE, policy.denialMessage)
                .set(table.STARTS_AT, policy.effectiveWindow.startsAt.toOffsetDateTime())
                .set(table.ENDS_AT, policy.effectiveWindow.endsAt?.toOffsetDateTime())
                .set(table.METADATA, policy.metadata.toJsonb())
            policy.createdAt?.let { insert.set(table.CREATED_AT, it.toOffsetDateTime()) }
            policy.updatedAt?.let { insert.set(table.UPDATED_AT, it.toOffsetDateTime()) }
            requireNotNull(insert.returning(table.fields().toList()).fetchOne()).toPolicy()
        }

    override fun update(tenantId: TenantId, policy: LimitChangePolicy): LimitChangePolicy =
        jooqOperation("update limit change policy") {
            require(policy.tenantId == tenantId) { "limit change policy tenant does not match repository tenant" }
            val update = context.dsl()
                .update(table)
                .set(table.CODE, policy.code.value)
                .set(table.NAME, policy.name.value)
                .set(table.DESCRIPTION, policy.description)
                .set(table.PHASE, policy.phase.toGenerated())
                .set(table.ENGINE, policy.engine.toGenerated())
                .set(table.DECISION, policy.decision.toGenerated())
                .set(table.LIMIT_CONFIGURATION_ID, policy.limitConfigurationId?.value)
                .set(table.PRIORITY, policy.priority.value)
                .set(table.IS_ACTIVE, policy.isActive)
                .set(table.APPLIES_TO, policy.appliesTo.toJsonb())
                .set(table.CONDITION_EXPRESSION, policy.conditionExpression.toJsonb())
                .set(table.ACTION_EXPRESSION, policy.actionExpression.toJsonb())
                .set(table.DENIAL_CODE, policy.denialCode)
                .set(table.DENIAL_MESSAGE, policy.denialMessage)
                .set(table.STARTS_AT, policy.effectiveWindow.startsAt.toOffsetDateTime())
                .set(table.ENDS_AT, policy.effectiveWindow.endsAt?.toOffsetDateTime())
                .set(table.METADATA, policy.metadata.toJsonb())
            policy.updatedAt?.let { update.set(table.UPDATED_AT, it.toOffsetDateTime()) }
            requireNotNull(
                update.where(table.TENANT_ID.eq(tenantId.value))
                    .and(table.ID.eq(policy.id.value))
                    .returning(table.fields().toList())
                    .fetchOne()
            ) { "limit change policy not found: ${policy.id.value}" }.toPolicy()
        }

    private fun selectPolicies() = context.dsl()
        .select(table.fields().toList())
        .from(table)

    private fun Record.toPolicy(): LimitChangePolicy = LimitChangePolicy(
        id = LimitChangePolicyId.of(requireNotNull(get(table.ID))),
        tenantId = TenantId.of(requireNotNull(get(table.TENANT_ID))),
        code = LimitChangePolicyCode.of(requireNotNull(get(table.CODE))),
        name = LimitChangePolicyName.of(requireNotNull(get(table.NAME))),
        phase = LimitChangePolicyPhase.valueOf(requireNotNull(get(table.PHASE)).name),
        effectiveWindow = EffectiveWindow(
            startsAt = requireNotNull(get(table.STARTS_AT)).toInstant(),
            endsAt = get(table.ENDS_AT)?.toInstant(),
        ),
        description = get(table.DESCRIPTION),
        engine = LimitChangePolicyEngine.valueOf(requireNotNull(get(table.ENGINE)).name),
        decision = LimitChangePolicyDecision.valueOf(requireNotNull(get(table.DECISION)).name),
        limitConfigurationId = get(table.LIMIT_CONFIGURATION_ID)?.let(LimitConfigurationId::of),
        priority = LimitPriority.of(requireNotNull(get(table.PRIORITY))),
        isActive = requireNotNull(get(table.IS_ACTIVE)),
        appliesTo = get(table.APPLIES_TO).toMetadata(),
        conditionExpression = get(table.CONDITION_EXPRESSION).toMetadata(),
        actionExpression = get(table.ACTION_EXPRESSION).toObjectList("policy action expression"),
        denialCode = get(table.DENIAL_CODE),
        denialMessage = get(table.DENIAL_MESSAGE),
        metadata = get(table.METADATA).toMetadata(),
        createdAt = get(table.CREATED_AT)?.toInstant(),
        updatedAt = get(table.UPDATED_AT)?.toInstant(),
    )

    private fun LimitChangePolicyPhase.toGenerated() =
        GeneratedLimitChangePolicyPhase.valueOf(name)

    private fun LimitChangePolicyEngine.toGenerated() =
        GeneratedLimitChangePolicyEngine.valueOf(name)

    private fun LimitChangePolicyDecision.toGenerated() =
        GeneratedLimitChangePolicyDecision.valueOf(name)
}

class JooqLimitChangePolicyEvaluationRepository(
    private val context: JooqPersistenceContext,
) : LimitChangePolicyEvaluationRepository {
    private val table = GeneratedLimitChangePolicyEvaluationTable.LIMIT_CHANGE_POLICY_EVALUATION

    override fun findById(
        tenantId: TenantId,
        id: LimitChangePolicyEvaluationId,
    ): LimitChangePolicyEvaluation? = jooqOperation("find policy evaluation by id") {
        selectEvaluations()
            .where(table.TENANT_ID.eq(tenantId.value))
            .and(table.ID.eq(id.value))
            .fetchOne { it.toEvaluation() }
    }

    override fun findByChangeRequest(
        tenantId: TenantId,
        changeRequestId: LimitChangeRequestId,
    ): List<LimitChangePolicyEvaluation> = jooqOperation("find policy evaluations by change request") {
        selectEvaluations()
            .where(table.TENANT_ID.eq(tenantId.value))
            .and(table.CHANGE_REQUEST_ID.eq(changeRequestId.value))
            .orderBy(table.EVALUATED_AT, table.ID)
            .fetch { it.toEvaluation() }
    }

    override fun insert(
        tenantId: TenantId,
        evaluation: LimitChangePolicyEvaluation,
    ): LimitChangePolicyEvaluation = jooqOperation("insert policy evaluation") {
        require(evaluation.tenantId == tenantId) { "policy evaluation tenant does not match repository tenant" }
        val insert = context.dsl()
            .insertInto(table)
            .set(table.ID, evaluation.id.value)
            .set(table.TENANT_ID, tenantId.value)
            .set(table.CHANGE_REQUEST_ID, evaluation.changeRequestId.value)
            .set(table.POLICY_ID, evaluation.policyId.value)
            .set(table.PHASE, evaluation.phase.toGenerated())
            .set(table.OUTCOME, evaluation.outcome.toGenerated())
            .set(table.DECISION, evaluation.decision.toGenerated())
            .set(table.MATCHED, evaluation.matched)
            .set(table.POLICY_SNAPSHOT, evaluation.policySnapshot.toJsonb())
            .set(table.INPUT_CONTEXT, evaluation.inputContext.toJsonb())
            .set(table.ACTION_RESULT, evaluation.actionResult.toJsonb())
            .set(table.MESSAGE, evaluation.message)
        evaluation.evaluatedAt?.let { insert.set(table.EVALUATED_AT, it.toOffsetDateTime()) }
        requireNotNull(insert.returning(table.fields().toList()).fetchOne()).toEvaluation()
    }

    private fun selectEvaluations() = context.dsl()
        .select(table.fields().toList())
        .from(table)

    private fun Record.toEvaluation(): LimitChangePolicyEvaluation = LimitChangePolicyEvaluation(
        id = LimitChangePolicyEvaluationId.of(requireNotNull(get(table.ID))),
        tenantId = TenantId.of(requireNotNull(get(table.TENANT_ID))),
        changeRequestId = LimitChangeRequestId.of(requireNotNull(get(table.CHANGE_REQUEST_ID))),
        policyId = LimitChangePolicyId.of(requireNotNull(get(table.POLICY_ID))),
        phase = LimitChangePolicyPhase.valueOf(requireNotNull(get(table.PHASE)).name),
        outcome = LimitChangePolicyEvaluationOutcome.valueOf(requireNotNull(get(table.OUTCOME)).name),
        decision = LimitChangePolicyDecision.valueOf(requireNotNull(get(table.DECISION)).name),
        matched = requireNotNull(get(table.MATCHED)),
        policySnapshot = get(table.POLICY_SNAPSHOT).toMetadata(),
        inputContext = get(table.INPUT_CONTEXT).toMetadata(),
        actionResult = get(table.ACTION_RESULT).toMetadata(),
        message = get(table.MESSAGE),
        evaluatedAt = get(table.EVALUATED_AT)?.toInstant(),
    )

    private fun LimitChangePolicyPhase.toGenerated() =
        GeneratedLimitChangePolicyPhase.valueOf(name)

    private fun LimitChangePolicyEvaluationOutcome.toGenerated() =
        GeneratedLimitChangePolicyEvaluationOutcome.valueOf(name)

    private fun LimitChangePolicyDecision.toGenerated() =
        GeneratedLimitChangePolicyDecision.valueOf(name)
}

private fun Instant.toOffsetDateTime(): OffsetDateTime = OffsetDateTime.ofInstant(this, ZoneOffset.UTC)
