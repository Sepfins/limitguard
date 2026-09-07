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
import io.github.sepfins.limitguard.infrastructure.jooq.jooqField
import io.github.sepfins.limitguard.infrastructure.jooq.jooqOperation
import io.github.sepfins.limitguard.infrastructure.jooq.jooqTable
import io.github.sepfins.limitguard.infrastructure.jooq.nullablePostgresEnum
import io.github.sepfins.limitguard.infrastructure.jooq.postgresEnum
import io.github.sepfins.limitguard.infrastructure.jooq.toJsonb
import io.github.sepfins.limitguard.infrastructure.jooq.toMetadata
import io.github.sepfins.limitguard.port.persistence.LimitRuleChangeEventRepository
import io.github.sepfins.limitguard.port.persistence.LimitRuleRevisionRepository
import org.jooq.Field
import org.jooq.JSONB
import org.jooq.Record
import org.jooq.SelectFieldOrAsterisk
import java.time.Instant
import java.util.UUID

class JooqLimitRuleChangeEventRepository(
    private val context: JooqPersistenceContext,
) : LimitRuleChangeEventRepository {
    override fun findById(tenantId: TenantId, id: LimitRuleChangeEventId): LimitRuleChangeEvent? =
        jooqOperation("find limit rule change event by id") {
            selectEvents()
                .where(LimitRuleChangeEventTable.tenantId.eq(tenantId.value))
                .and(LimitRuleChangeEventTable.id.eq(id.value))
                .fetchOne { it.toEvent() }
        }

    override fun findByChangeRequest(
        tenantId: TenantId,
        changeRequestId: LimitChangeRequestId,
    ): List<LimitRuleChangeEvent> = jooqOperation("find limit rule change events by request") {
        selectEvents()
            .where(LimitRuleChangeEventTable.tenantId.eq(tenantId.value))
            .and(LimitRuleChangeEventTable.changeRequestId.eq(changeRequestId.value))
            .orderBy(LimitRuleChangeEventTable.createdAt, LimitRuleChangeEventTable.id)
            .fetch { it.toEvent() }
    }

    override fun insert(tenantId: TenantId, event: LimitRuleChangeEvent): LimitRuleChangeEvent =
        jooqOperation("insert limit rule change event") {
            require(event.tenantId == tenantId) { "limit rule change event tenant does not match repository tenant" }
            val insert = context.dsl()
                .insertInto(LimitRuleChangeEventTable.table)
                .set(LimitRuleChangeEventTable.id, event.id.value)
                .set(LimitRuleChangeEventTable.tenantId, tenantId.value)
                .set(LimitRuleChangeEventTable.changeRequestId, event.changeRequestId.value)
                .set(
                    LimitRuleChangeEventTable.eventType,
                    postgresEnum(event.eventType.name, "limit_rule_change_event_type"),
                )
                .set(
                    LimitRuleChangeEventTable.actorType,
                    postgresEnum(event.actor.typeName(), "limit_change_actor_type"),
                )
                .set(LimitRuleChangeEventTable.actorPartyProfileId, event.actor.partyProfileId())
                .set(LimitRuleChangeEventTable.actorBankWorkerId, event.actor.bankWorkerId())
                .set(LimitRuleChangeEventTable.policyId, event.policyId?.value)
                .set(
                    LimitRuleChangeEventTable.beforeStatus,
                    nullablePostgresEnum(event.beforeStatus?.name, "limit_rule_change_status"),
                )
                .set(
                    LimitRuleChangeEventTable.afterStatus,
                    nullablePostgresEnum(event.afterStatus?.name, "limit_rule_change_status"),
                )
                .set(LimitRuleChangeEventTable.beforeRule, event.beforeRule?.toJsonb())
                .set(LimitRuleChangeEventTable.afterRule, event.afterRule?.toJsonb())
                .set(LimitRuleChangeEventTable.eventPayload, event.eventPayload.toJsonb())
                .set(LimitRuleChangeEventTable.message, event.message)
            event.createdAt?.let { insert.set(LimitRuleChangeEventTable.createdAt, it) }
            requireNotNull(insert.returning(LimitRuleChangeEventTable.fields).fetchOne()).toEvent()
        }

    private fun selectEvents() = context.dsl()
        .select(LimitRuleChangeEventTable.fields)
        .from(LimitRuleChangeEventTable.table)

    private fun Record.toEvent(): LimitRuleChangeEvent = LimitRuleChangeEvent(
        id = LimitRuleChangeEventId.of(requireNotNull(get(LimitRuleChangeEventTable.id))),
        tenantId = TenantId.of(requireNotNull(get(LimitRuleChangeEventTable.tenantId))),
        changeRequestId = LimitChangeRequestId.of(
            requireNotNull(get(LimitRuleChangeEventTable.changeRequestId))
        ),
        eventType = LimitRuleChangeEventType.valueOf(
            requireNotNull(get(LimitRuleChangeEventTable.eventType))
        ),
        actor = actorOf(
            requireNotNull(get(LimitRuleChangeEventTable.actorType)),
            get(LimitRuleChangeEventTable.actorPartyProfileId),
            get(LimitRuleChangeEventTable.actorBankWorkerId),
        ),
        policyId = get(LimitRuleChangeEventTable.policyId)?.let(LimitChangePolicyId::of),
        beforeStatus = get(LimitRuleChangeEventTable.beforeStatus)?.let(LimitRuleChangeStatus::valueOf),
        afterStatus = get(LimitRuleChangeEventTable.afterStatus)?.let(LimitRuleChangeStatus::valueOf),
        beforeRule = get(LimitRuleChangeEventTable.beforeRule)?.toMetadata(),
        afterRule = get(LimitRuleChangeEventTable.afterRule)?.toMetadata(),
        eventPayload = get(LimitRuleChangeEventTable.eventPayload).toMetadata(),
        message = get(LimitRuleChangeEventTable.message),
        createdAt = get(LimitRuleChangeEventTable.createdAt),
    )
}

class JooqLimitRuleRevisionRepository(
    private val context: JooqPersistenceContext,
) : LimitRuleRevisionRepository {
    override fun findById(tenantId: TenantId, id: LimitRuleRevisionId): LimitRuleRevision? =
        jooqOperation("find limit rule revision by id") {
            selectRevisions()
                .where(LimitRuleRevisionTable.tenantId.eq(tenantId.value))
                .and(LimitRuleRevisionTable.id.eq(id.value))
                .fetchOne { it.toRevision() }
        }

    override fun findByLimitRule(
        tenantId: TenantId,
        limitRuleId: LimitRuleId,
    ): List<LimitRuleRevision> = jooqOperation("find revisions by limit rule") {
        selectRevisions()
            .where(LimitRuleRevisionTable.tenantId.eq(tenantId.value))
            .and(LimitRuleRevisionTable.limitRuleId.eq(limitRuleId.value))
            .orderBy(LimitRuleRevisionTable.revisionNumber, LimitRuleRevisionTable.id)
            .fetch { it.toRevision() }
    }

    override fun findByChangeRequest(
        tenantId: TenantId,
        changeRequestId: LimitChangeRequestId,
    ): List<LimitRuleRevision> = jooqOperation("find revisions by change request") {
        selectRevisions()
            .where(LimitRuleRevisionTable.tenantId.eq(tenantId.value))
            .and(LimitRuleRevisionTable.changeRequestId.eq(changeRequestId.value))
            .orderBy(LimitRuleRevisionTable.revisionNumber, LimitRuleRevisionTable.id)
            .fetch { it.toRevision() }
    }

    override fun insert(tenantId: TenantId, revision: LimitRuleRevision): LimitRuleRevision =
        jooqOperation("insert limit rule revision") {
            require(revision.tenantId == tenantId) { "limit rule revision tenant does not match repository tenant" }
            val insert = context.dsl()
                .insertInto(LimitRuleRevisionTable.table)
                .set(LimitRuleRevisionTable.id, revision.id.value)
                .set(LimitRuleRevisionTable.tenantId, tenantId.value)
                .set(LimitRuleRevisionTable.limitRuleId, revision.limitRuleId.value)
                .set(LimitRuleRevisionTable.changeRequestId, revision.changeRequestId?.value)
                .set(LimitRuleRevisionTable.revisionNumber, revision.revisionNumber.value)
                .set(
                    LimitRuleRevisionTable.revisionEventType,
                    postgresEnum(revision.revisionEventType.name, "limit_rule_revision_event_type"),
                )
                .set(
                    LimitRuleRevisionTable.actorType,
                    postgresEnum(revision.actor.typeName(), "limit_change_actor_type"),
                )
                .set(LimitRuleRevisionTable.actorPartyProfileId, revision.actor.partyProfileId())
                .set(LimitRuleRevisionTable.actorBankWorkerId, revision.actor.bankWorkerId())
                .set(LimitRuleRevisionTable.beforeRule, revision.beforeRule?.toJsonb())
                .set(LimitRuleRevisionTable.afterRule, revision.afterRule?.toJsonb())
                .set(LimitRuleRevisionTable.metadata, revision.metadata.toJsonb())
            revision.effectiveAt?.let { insert.set(LimitRuleRevisionTable.effectiveAt, it) }
            revision.createdAt?.let { insert.set(LimitRuleRevisionTable.createdAt, it) }
            requireNotNull(insert.returning(LimitRuleRevisionTable.fields).fetchOne()).toRevision()
        }

    private fun selectRevisions() = context.dsl()
        .select(LimitRuleRevisionTable.fields)
        .from(LimitRuleRevisionTable.table)

    private fun Record.toRevision(): LimitRuleRevision = LimitRuleRevision(
        id = LimitRuleRevisionId.of(requireNotNull(get(LimitRuleRevisionTable.id))),
        tenantId = TenantId.of(requireNotNull(get(LimitRuleRevisionTable.tenantId))),
        limitRuleId = LimitRuleId.of(requireNotNull(get(LimitRuleRevisionTable.limitRuleId))),
        revisionNumber = RevisionNumber.of(requireNotNull(get(LimitRuleRevisionTable.revisionNumber))),
        revisionEventType = LimitRuleRevisionEventType.valueOf(
            requireNotNull(get(LimitRuleRevisionTable.revisionEventType))
        ),
        actor = actorOf(
            requireNotNull(get(LimitRuleRevisionTable.actorType)),
            get(LimitRuleRevisionTable.actorPartyProfileId),
            get(LimitRuleRevisionTable.actorBankWorkerId),
        ),
        changeRequestId = get(LimitRuleRevisionTable.changeRequestId)?.let(LimitChangeRequestId::of),
        beforeRule = get(LimitRuleRevisionTable.beforeRule)?.toMetadata(),
        afterRule = get(LimitRuleRevisionTable.afterRule)?.toMetadata(),
        effectiveAt = get(LimitRuleRevisionTable.effectiveAt),
        metadata = get(LimitRuleRevisionTable.metadata).toMetadata(),
        createdAt = get(LimitRuleRevisionTable.createdAt),
    )
}

private fun ChangeActor.typeName(): String = when (this) {
    ChangeActor.System -> "SYSTEM"
    is ChangeActor.PartyProfileActor -> "PARTY_PROFILE"
    is ChangeActor.BankWorkerActor -> "BANK_WORKER"
}

private fun ChangeActor.partyProfileId(): UUID? =
    (this as? ChangeActor.PartyProfileActor)?.partyProfileId?.value

private fun ChangeActor.bankWorkerId(): UUID? =
    (this as? ChangeActor.BankWorkerActor)?.bankWorkerId?.value

private fun actorOf(type: String, partyProfileId: UUID?, bankWorkerId: UUID?): ChangeActor = when (type) {
    "SYSTEM" -> ChangeActor.System
    "PARTY_PROFILE" -> ChangeActor.PartyProfileActor(PartyProfileId.of(requireNotNull(partyProfileId)))
    "BANK_WORKER" -> ChangeActor.BankWorkerActor(BankWorkerId.of(requireNotNull(bankWorkerId)))
    else -> error("unsupported limit change actor type: $type")
}

private object LimitRuleChangeEventTable {
    const val NAME = "limit_rule_change_event"
    val table = jooqTable(NAME)
    val id: Field<UUID> = jooqField(NAME, "id", UUID::class.java)
    val tenantId: Field<UUID> = jooqField(NAME, "tenant_id", UUID::class.java)
    val changeRequestId: Field<UUID> = jooqField(NAME, "change_request_id", UUID::class.java)
    val eventType: Field<String> = jooqField(NAME, "event_type", String::class.java)
    val actorType: Field<String> = jooqField(NAME, "actor_type", String::class.java)
    val actorPartyProfileId: Field<UUID> = jooqField(NAME, "actor_party_profile_id", UUID::class.java)
    val actorBankWorkerId: Field<UUID> = jooqField(NAME, "actor_bank_worker_id", UUID::class.java)
    val policyId: Field<UUID> = jooqField(NAME, "policy_id", UUID::class.java)
    val beforeStatus: Field<String> = jooqField(NAME, "before_status", String::class.java)
    val afterStatus: Field<String> = jooqField(NAME, "after_status", String::class.java)
    val beforeRule: Field<JSONB> = jooqField(NAME, "before_rule", JSONB::class.java)
    val afterRule: Field<JSONB> = jooqField(NAME, "after_rule", JSONB::class.java)
    val eventPayload: Field<JSONB> = jooqField(NAME, "event_payload", JSONB::class.java)
    val message: Field<String> = jooqField(NAME, "message", String::class.java)
    val createdAt: Field<Instant> = jooqField(NAME, "created_at", Instant::class.java)
    val fields: List<SelectFieldOrAsterisk> = listOf(
        id,
        tenantId,
        changeRequestId,
        eventType,
        actorType,
        actorPartyProfileId,
        actorBankWorkerId,
        policyId,
        beforeStatus,
        afterStatus,
        beforeRule,
        afterRule,
        eventPayload,
        message,
        createdAt,
    )
}

private object LimitRuleRevisionTable {
    const val NAME = "limit_rule_revision"
    val table = jooqTable(NAME)
    val id: Field<UUID> = jooqField(NAME, "id", UUID::class.java)
    val tenantId: Field<UUID> = jooqField(NAME, "tenant_id", UUID::class.java)
    val limitRuleId: Field<UUID> = jooqField(NAME, "limit_rule_id", UUID::class.java)
    val changeRequestId: Field<UUID> = jooqField(NAME, "change_request_id", UUID::class.java)
    val revisionNumber: Field<Int> = jooqField(NAME, "revision_number", Int::class.java)
    val revisionEventType: Field<String> = jooqField(NAME, "revision_event_type", String::class.java)
    val actorType: Field<String> = jooqField(NAME, "actor_type", String::class.java)
    val actorPartyProfileId: Field<UUID> = jooqField(NAME, "actor_party_profile_id", UUID::class.java)
    val actorBankWorkerId: Field<UUID> = jooqField(NAME, "actor_bank_worker_id", UUID::class.java)
    val beforeRule: Field<JSONB> = jooqField(NAME, "before_rule", JSONB::class.java)
    val afterRule: Field<JSONB> = jooqField(NAME, "after_rule", JSONB::class.java)
    val effectiveAt: Field<Instant> = jooqField(NAME, "effective_at", Instant::class.java)
    val metadata: Field<JSONB> = jooqField(NAME, "metadata", JSONB::class.java)
    val createdAt: Field<Instant> = jooqField(NAME, "created_at", Instant::class.java)
    val fields: List<SelectFieldOrAsterisk> = listOf(
        id,
        tenantId,
        limitRuleId,
        changeRequestId,
        revisionNumber,
        revisionEventType,
        actorType,
        actorPartyProfileId,
        actorBankWorkerId,
        beforeRule,
        afterRule,
        effectiveAt,
        metadata,
        createdAt,
    )
}
