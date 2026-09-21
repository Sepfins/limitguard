package io.github.sepfins.limitguard.infrastructure.jooq.repository

import io.github.sepfins.limitguard.domain.change.ChangeActor
import io.github.sepfins.limitguard.domain.change.LimitRuleChangeEvent
import io.github.sepfins.limitguard.domain.change.LimitRuleChangeEventType
import io.github.sepfins.limitguard.domain.change.LimitRuleChangeStatus
import io.github.sepfins.limitguard.domain.change.LimitRuleRevision
import io.github.sepfins.limitguard.domain.change.LimitRuleRevisionEventType
import io.github.sepfins.limitguard.domain.change.RevisionNumber
import io.github.sepfins.limitguard.domain.common.BankWorkerId
import io.github.sepfins.limitguard.domain.common.LimitChangePolicyId
import io.github.sepfins.limitguard.domain.common.LimitChangeRequestId
import io.github.sepfins.limitguard.domain.common.LimitRuleChangeEventId
import io.github.sepfins.limitguard.domain.common.LimitRuleId
import io.github.sepfins.limitguard.domain.common.LimitRuleRevisionId
import io.github.sepfins.limitguard.domain.common.PartyProfileId
import io.github.sepfins.limitguard.domain.common.TenantId
import io.github.sepfins.limitguard.infrastructure.jooq.JooqPersistenceContext
import io.github.sepfins.limitguard.infrastructure.jooq.jooqOperation
import io.github.sepfins.limitguard.infrastructure.jooq.toJsonb
import io.github.sepfins.limitguard.infrastructure.jooq.toMetadata
import io.github.sepfins.limitguard.infrastructure.jooq.generated.enums.LimitChangeActorType as GeneratedLimitChangeActorType
import io.github.sepfins.limitguard.infrastructure.jooq.generated.enums.LimitRuleChangeEventType as GeneratedLimitRuleChangeEventType
import io.github.sepfins.limitguard.infrastructure.jooq.generated.enums.LimitRuleChangeStatus as GeneratedLimitRuleChangeStatus
import io.github.sepfins.limitguard.infrastructure.jooq.generated.enums.LimitRuleRevisionEventType as GeneratedLimitRuleRevisionEventType
import io.github.sepfins.limitguard.infrastructure.jooq.generated.tables.LimitRuleChangeEvent as GeneratedLimitRuleChangeEventTable
import io.github.sepfins.limitguard.infrastructure.jooq.generated.tables.LimitRuleRevision as GeneratedLimitRuleRevisionTable
import io.github.sepfins.limitguard.port.persistence.LimitRuleChangeEventRepository
import io.github.sepfins.limitguard.port.persistence.LimitRuleRevisionRepository
import org.jooq.Record
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

class JooqLimitRuleChangeEventRepository(
    private val context: JooqPersistenceContext,
) : LimitRuleChangeEventRepository {
    private val table = GeneratedLimitRuleChangeEventTable.LIMIT_RULE_CHANGE_EVENT

    override fun findById(tenantId: TenantId, id: LimitRuleChangeEventId): LimitRuleChangeEvent? =
        jooqOperation("find limit rule change event by id") {
            selectEvents()
                .where(table.TENANT_ID.eq(tenantId.value))
                .and(table.ID.eq(id.value))
                .fetchOne { it.toEvent() }
        }

    override fun findByChangeRequest(
        tenantId: TenantId,
        changeRequestId: LimitChangeRequestId,
    ): List<LimitRuleChangeEvent> = jooqOperation("find limit rule change events by request") {
        selectEvents()
            .where(table.TENANT_ID.eq(tenantId.value))
            .and(table.CHANGE_REQUEST_ID.eq(changeRequestId.value))
            .orderBy(table.CREATED_AT, table.ID)
            .fetch { it.toEvent() }
    }

    override fun insert(tenantId: TenantId, event: LimitRuleChangeEvent): LimitRuleChangeEvent =
        jooqOperation("insert limit rule change event") {
            require(event.tenantId == tenantId) { "limit rule change event tenant does not match repository tenant" }
            val insert = context.dsl()
                .insertInto(table)
                .set(table.ID, event.id.value)
                .set(table.TENANT_ID, tenantId.value)
                .set(table.CHANGE_REQUEST_ID, event.changeRequestId.value)
                .set(table.EVENT_TYPE, event.eventType.toGenerated())
                .set(table.ACTOR_TYPE, event.actor.toGenerated())
                .set(table.ACTOR_PARTY_PROFILE_ID, event.actor.partyProfileId())
                .set(table.ACTOR_BANK_WORKER_ID, event.actor.bankWorkerId())
                .set(table.POLICY_ID, event.policyId?.value)
                .set(table.BEFORE_STATUS, event.beforeStatus?.toGenerated())
                .set(table.AFTER_STATUS, event.afterStatus?.toGenerated())
                .set(table.BEFORE_RULE, event.beforeRule?.toJsonb())
                .set(table.AFTER_RULE, event.afterRule?.toJsonb())
                .set(table.EVENT_PAYLOAD, event.eventPayload.toJsonb())
                .set(table.MESSAGE, event.message)
            event.createdAt?.let { insert.set(table.CREATED_AT, it.toOffsetDateTime()) }
            requireNotNull(insert.returning(table.fields().toList()).fetchOne()).toEvent()
        }

    private fun selectEvents() = context.dsl()
        .select(table.fields().toList())
        .from(table)

    private fun Record.toEvent(): LimitRuleChangeEvent = LimitRuleChangeEvent(
        id = LimitRuleChangeEventId.of(requireNotNull(get(table.ID))),
        tenantId = TenantId.of(requireNotNull(get(table.TENANT_ID))),
        changeRequestId = LimitChangeRequestId.of(requireNotNull(get(table.CHANGE_REQUEST_ID))),
        eventType = LimitRuleChangeEventType.valueOf(requireNotNull(get(table.EVENT_TYPE)).name),
        actor = actorOf(
            requireNotNull(get(table.ACTOR_TYPE)),
            get(table.ACTOR_PARTY_PROFILE_ID),
            get(table.ACTOR_BANK_WORKER_ID),
        ),
        policyId = get(table.POLICY_ID)?.let(LimitChangePolicyId::of),
        beforeStatus = get(table.BEFORE_STATUS)?.let { LimitRuleChangeStatus.valueOf(it.name) },
        afterStatus = get(table.AFTER_STATUS)?.let { LimitRuleChangeStatus.valueOf(it.name) },
        beforeRule = get(table.BEFORE_RULE)?.toMetadata(),
        afterRule = get(table.AFTER_RULE)?.toMetadata(),
        eventPayload = get(table.EVENT_PAYLOAD).toMetadata(),
        message = get(table.MESSAGE),
        createdAt = get(table.CREATED_AT)?.toInstant(),
    )
}

class JooqLimitRuleRevisionRepository(
    private val context: JooqPersistenceContext,
) : LimitRuleRevisionRepository {
    private val table = GeneratedLimitRuleRevisionTable.LIMIT_RULE_REVISION

    override fun findById(tenantId: TenantId, id: LimitRuleRevisionId): LimitRuleRevision? =
        jooqOperation("find limit rule revision by id") {
            selectRevisions()
                .where(table.TENANT_ID.eq(tenantId.value))
                .and(table.ID.eq(id.value))
                .fetchOne { it.toRevision() }
        }

    override fun findByLimitRule(
        tenantId: TenantId,
        limitRuleId: LimitRuleId,
    ): List<LimitRuleRevision> = jooqOperation("find revisions by limit rule") {
        selectRevisions()
            .where(table.TENANT_ID.eq(tenantId.value))
            .and(table.LIMIT_RULE_ID.eq(limitRuleId.value))
            .orderBy(table.REVISION_NUMBER, table.ID)
            .fetch { it.toRevision() }
    }

    override fun findByChangeRequest(
        tenantId: TenantId,
        changeRequestId: LimitChangeRequestId,
    ): List<LimitRuleRevision> = jooqOperation("find revisions by change request") {
        selectRevisions()
            .where(table.TENANT_ID.eq(tenantId.value))
            .and(table.CHANGE_REQUEST_ID.eq(changeRequestId.value))
            .orderBy(table.REVISION_NUMBER, table.ID)
            .fetch { it.toRevision() }
    }

    override fun insert(tenantId: TenantId, revision: LimitRuleRevision): LimitRuleRevision =
        jooqOperation("insert limit rule revision") {
            require(revision.tenantId == tenantId) { "limit rule revision tenant does not match repository tenant" }
            val insert = context.dsl()
                .insertInto(table)
                .set(table.ID, revision.id.value)
                .set(table.TENANT_ID, tenantId.value)
                .set(table.LIMIT_RULE_ID, revision.limitRuleId.value)
                .set(table.CHANGE_REQUEST_ID, revision.changeRequestId?.value)
                .set(table.REVISION_NUMBER, revision.revisionNumber.value)
                .set(table.REVISION_EVENT_TYPE, revision.revisionEventType.toGenerated())
                .set(table.ACTOR_TYPE, revision.actor.toGenerated())
                .set(table.ACTOR_PARTY_PROFILE_ID, revision.actor.partyProfileId())
                .set(table.ACTOR_BANK_WORKER_ID, revision.actor.bankWorkerId())
                .set(table.BEFORE_RULE, revision.beforeRule?.toJsonb())
                .set(table.AFTER_RULE, revision.afterRule?.toJsonb())
                .set(table.METADATA, revision.metadata.toJsonb())
            revision.effectiveAt?.let { insert.set(table.EFFECTIVE_AT, it.toOffsetDateTime()) }
            revision.createdAt?.let { insert.set(table.CREATED_AT, it.toOffsetDateTime()) }
            requireNotNull(insert.returning(table.fields().toList()).fetchOne()).toRevision()
        }

    private fun selectRevisions() = context.dsl()
        .select(table.fields().toList())
        .from(table)

    private fun Record.toRevision(): LimitRuleRevision = LimitRuleRevision(
        id = LimitRuleRevisionId.of(requireNotNull(get(table.ID))),
        tenantId = TenantId.of(requireNotNull(get(table.TENANT_ID))),
        limitRuleId = LimitRuleId.of(requireNotNull(get(table.LIMIT_RULE_ID))),
        revisionNumber = RevisionNumber.of(requireNotNull(get(table.REVISION_NUMBER))),
        revisionEventType = LimitRuleRevisionEventType.valueOf(
            requireNotNull(get(table.REVISION_EVENT_TYPE)).name
        ),
        actor = actorOf(
            requireNotNull(get(table.ACTOR_TYPE)),
            get(table.ACTOR_PARTY_PROFILE_ID),
            get(table.ACTOR_BANK_WORKER_ID),
        ),
        changeRequestId = get(table.CHANGE_REQUEST_ID)?.let(LimitChangeRequestId::of),
        beforeRule = get(table.BEFORE_RULE)?.toMetadata(),
        afterRule = get(table.AFTER_RULE)?.toMetadata(),
        effectiveAt = get(table.EFFECTIVE_AT)?.toInstant(),
        metadata = get(table.METADATA).toMetadata(),
        createdAt = get(table.CREATED_AT)?.toInstant(),
    )
}

private fun ChangeActor.toGenerated(): GeneratedLimitChangeActorType = when (this) {
    ChangeActor.System -> GeneratedLimitChangeActorType.SYSTEM
    is ChangeActor.PartyProfileActor -> GeneratedLimitChangeActorType.PARTY_PROFILE
    is ChangeActor.BankWorkerActor -> GeneratedLimitChangeActorType.BANK_WORKER
}

private fun ChangeActor.partyProfileId(): java.util.UUID? =
    (this as? ChangeActor.PartyProfileActor)?.partyProfileId?.value

private fun ChangeActor.bankWorkerId(): java.util.UUID? =
    (this as? ChangeActor.BankWorkerActor)?.bankWorkerId?.value

private fun actorOf(
    type: GeneratedLimitChangeActorType,
    partyProfileId: java.util.UUID?,
    bankWorkerId: java.util.UUID?,
): ChangeActor = when (type) {
    GeneratedLimitChangeActorType.SYSTEM -> ChangeActor.System
    GeneratedLimitChangeActorType.PARTY_PROFILE ->
        ChangeActor.PartyProfileActor(PartyProfileId.of(requireNotNull(partyProfileId)))
    GeneratedLimitChangeActorType.BANK_WORKER ->
        ChangeActor.BankWorkerActor(BankWorkerId.of(requireNotNull(bankWorkerId)))
}

private fun LimitRuleChangeEventType.toGenerated() = GeneratedLimitRuleChangeEventType.valueOf(name)

private fun LimitRuleChangeStatus.toGenerated() = GeneratedLimitRuleChangeStatus.valueOf(name)

private fun LimitRuleRevisionEventType.toGenerated() = GeneratedLimitRuleRevisionEventType.valueOf(name)

private fun Instant.toOffsetDateTime(): OffsetDateTime = OffsetDateTime.ofInstant(this, ZoneOffset.UTC)
