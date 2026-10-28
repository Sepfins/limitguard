package io.github.sepfins.limitguard.infrastructure.jooq.repository

import io.github.sepfins.limitguard.domain.change.ChangeActor
import io.github.sepfins.limitguard.domain.change.LimitRuleChangeEvent
import io.github.sepfins.limitguard.domain.change.LimitRuleChangeEventType
import io.github.sepfins.limitguard.domain.change.LimitRuleChangeStatus
import io.github.sepfins.limitguard.domain.change.LimitRuleRevision
import io.github.sepfins.limitguard.domain.change.LimitRuleRevisionEventType
import io.github.sepfins.limitguard.domain.change.RevisionNumber
import io.github.sepfins.limitguard.domain.common.CurrencyCode
import io.github.sepfins.limitguard.domain.common.LimitChangePolicyId
import io.github.sepfins.limitguard.domain.common.LimitChangeRequestId
import io.github.sepfins.limitguard.domain.common.LimitRuleChangeEventId
import io.github.sepfins.limitguard.domain.common.LimitRuleId
import io.github.sepfins.limitguard.domain.common.LimitRuleRevisionId
import io.github.sepfins.limitguard.domain.common.PartyProfileId
import io.github.sepfins.limitguard.domain.common.TenantId
import io.github.sepfins.limitguard.domain.limit.LimitAmount
import io.github.sepfins.limitguard.infrastructure.jooq.JooqPersistenceContext
import io.github.sepfins.limitguard.infrastructure.jooq.jooqOperation
import io.github.sepfins.limitguard.infrastructure.jooq.storedAmount
import io.github.sepfins.limitguard.infrastructure.jooq.storedLimitAmount
import io.github.sepfins.limitguard.infrastructure.jooq.toJsonb
import io.github.sepfins.limitguard.infrastructure.jooq.toMetadata
import io.github.sepfins.limitguard.infrastructure.jooq.generated.enums.LimitChangeActorType as GeneratedLimitChangeActorType
import io.github.sepfins.limitguard.infrastructure.jooq.generated.enums.LimitRuleChangeEventType as GeneratedLimitRuleChangeEventType
import io.github.sepfins.limitguard.infrastructure.jooq.generated.enums.LimitRuleChangeStatus as GeneratedLimitRuleChangeStatus
import io.github.sepfins.limitguard.infrastructure.jooq.generated.enums.LimitRuleRevisionEventType as GeneratedLimitRuleRevisionEventType
import io.github.sepfins.limitguard.infrastructure.jooq.generated.tables.LimitConfiguration as GeneratedLimitConfigurationTable
import io.github.sepfins.limitguard.infrastructure.jooq.generated.tables.LimitRule as GeneratedLimitRuleTable
import io.github.sepfins.limitguard.infrastructure.jooq.generated.tables.LimitRuleChangeEvent as GeneratedLimitRuleChangeEventTable
import io.github.sepfins.limitguard.infrastructure.jooq.generated.tables.LimitRuleChangeEventOutbox as GeneratedLimitRuleChangeEventOutboxTable
import io.github.sepfins.limitguard.infrastructure.jooq.generated.tables.LimitRuleRevision as GeneratedLimitRuleRevisionTable
import io.github.sepfins.limitguard.port.persistence.LimitChangeEventOutbox
import io.github.sepfins.limitguard.port.persistence.LimitRuleChangeEventRepository
import io.github.sepfins.limitguard.port.persistence.LimitRuleRevisionRepository
import io.github.sepfins.limitguard.port.persistence.OutboxedLimitChangeEvent
import org.jooq.Record
import org.jooq.impl.DSL
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
                .set(table.POLICY_ID, event.policyId?.value)
                .set(table.BEFORE_STATUS, event.beforeStatus?.toGenerated())
                .set(table.AFTER_STATUS, event.afterStatus?.toGenerated())
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
        ),
        policyId = get(table.POLICY_ID)?.let(LimitChangePolicyId::of),
        beforeStatus = get(table.BEFORE_STATUS)?.let { LimitRuleChangeStatus.valueOf(it.name) },
        afterStatus = get(table.AFTER_STATUS)?.let { LimitRuleChangeStatus.valueOf(it.name) },
        eventPayload = get(table.EVENT_PAYLOAD).toMetadata(),
        message = get(table.MESSAGE),
        createdAt = get(table.CREATED_AT)?.toInstant(),
    )
}

class JooqLimitRuleRevisionRepository(
    private val context: JooqPersistenceContext,
) : LimitRuleRevisionRepository {
    private val table = GeneratedLimitRuleRevisionTable.LIMIT_RULE_REVISION
    private val rule = GeneratedLimitRuleTable.LIMIT_RULE
    private val configuration = GeneratedLimitConfigurationTable.LIMIT_CONFIGURATION

    override fun findById(tenantId: TenantId, id: LimitRuleRevisionId): LimitRuleRevision? =
        jooqOperation("find limit rule revision by id") {
            selectRevisions()
                .where(table.TENANT_ID.eq(tenantId.value))
                .and(table.ID.eq(id.value))
                .fetchOne { it.toRevision() }
        }

    override fun findLatestByLimitRule(
        tenantId: TenantId,
        limitRuleId: LimitRuleId,
    ): LimitRuleRevision? = jooqOperation("find latest revision by limit rule") {
        selectRevisions()
            .where(table.TENANT_ID.eq(tenantId.value))
            .and(table.LIMIT_RULE_ID.eq(limitRuleId.value))
            .orderBy(table.REVISION_NUMBER.desc())
            .limit(1)
            .fetchOne { it.toRevision() }
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
                .set(table.BEFORE_AMOUNT, revision.beforeAmount?.storedAmount)
                .set(table.BEFORE_UNLIMITED, revision.beforeAmount?.let { it is LimitAmount.Unlimited })
                .set(table.AFTER_AMOUNT, revision.afterAmount.storedAmount)
                .set(table.AFTER_UNLIMITED, revision.afterAmount is LimitAmount.Unlimited)
                .set(table.METADATA, revision.metadata.toJsonb())
            revision.effectiveAt?.let { insert.set(table.EFFECTIVE_AT, it.toOffsetDateTime()) }
            revision.createdAt?.let { insert.set(table.CREATED_AT, it.toOffsetDateTime()) }
            requireNotNull(insert.returning().fetchOne()).toRevision(revision.afterAmount.currency)
        }

    /** Amounts are in the rule's configuration currency, the only currency column. */
    private fun selectRevisions() = context.dsl()
        .select(table.fields().toList() + configuration.CURRENCY)
        .from(table)
        .join(rule).on(rule.TENANT_ID.eq(table.TENANT_ID)).and(rule.ID.eq(table.LIMIT_RULE_ID))
        .join(configuration).on(configuration.TENANT_ID.eq(rule.TENANT_ID)).and(configuration.ID.eq(rule.CONFIGURATION_ID))

    private fun Record.toRevision(): LimitRuleRevision =
        toRevision(CurrencyCode.of(requireNotNull(get(configuration.CURRENCY))))

    private fun Record.toRevision(currency: CurrencyCode): LimitRuleRevision {
        return LimitRuleRevision(
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
            ),
            changeRequestId = get(table.CHANGE_REQUEST_ID)?.let(LimitChangeRequestId::of),
            beforeAmount = get(table.BEFORE_UNLIMITED)?.let { storedLimitAmount(get(table.BEFORE_AMOUNT), it, currency) },
            afterAmount = storedLimitAmount(get(table.AFTER_AMOUNT), requireNotNull(get(table.AFTER_UNLIMITED)), currency),
            effectiveAt = get(table.EFFECTIVE_AT)?.toInstant(),
            metadata = get(table.METADATA).toMetadata(),
            createdAt = get(table.CREATED_AT)?.toInstant(),
        )
    }
}

class JooqLimitChangeEventOutbox(
    private val context: JooqPersistenceContext,
) : LimitChangeEventOutbox {
    private val table = GeneratedLimitRuleChangeEventOutboxTable.LIMIT_RULE_CHANGE_EVENT_OUTBOX

    override fun enqueue(tenantId: TenantId, eventId: LimitRuleChangeEventId) {
        jooqOperation("enqueue limit change event for relay") {
            context.dsl()
                .insertInto(table)
                .set(table.TENANT_ID, tenantId.value)
                .set(table.CHANGE_EVENT_ID, eventId.value)
                .execute()
        }
    }

    override fun lockOldest(limit: Int): List<OutboxedLimitChangeEvent> =
        jooqOperation("lock oldest relayable limit change events") {
            context.requireTransaction("relaying limit change events")
            // Scoped to the schema, so hosts sharing one database do not block each other's relay.
            val locked = context.dsl().fetchValue(
                "select pg_try_advisory_xact_lock(hashtextextended(current_schema() || ?, 0))",
                RELAY_LOCK_KEY,
            )
            if (locked != true) return@jooqOperation emptyList()
            context.dsl()
                .select(table.TENANT_ID, table.CHANGE_EVENT_ID)
                .from(table)
                .orderBy(table.ID)
                .limit(limit)
                .fetch {
                    OutboxedLimitChangeEvent(
                        tenantId = TenantId.of(requireNotNull(it.get(table.TENANT_ID))),
                        eventId = LimitRuleChangeEventId.of(requireNotNull(it.get(table.CHANGE_EVENT_ID))),
                    )
                }
        }

    override fun delete(entries: List<OutboxedLimitChangeEvent>) {
        jooqOperation("delete relayed limit change events") {
            context.dsl()
                .deleteFrom(table)
                .where(DSL.row(table.TENANT_ID, table.CHANGE_EVENT_ID).`in`(
                    entries.map { DSL.row(it.tenantId.value, it.eventId.value) },
                ))
                .execute()
        }
    }

    private companion object {
        const val RELAY_LOCK_KEY = "limitguard:limit-change-event-relay"
    }
}

private fun ChangeActor.toGenerated(): GeneratedLimitChangeActorType = when (this) {
    ChangeActor.System -> GeneratedLimitChangeActorType.SYSTEM
    is ChangeActor.PartyProfileActor -> GeneratedLimitChangeActorType.PARTY_PROFILE
}

private fun ChangeActor.partyProfileId(): java.util.UUID? =
    (this as? ChangeActor.PartyProfileActor)?.partyProfileId?.value

private fun actorOf(
    type: GeneratedLimitChangeActorType,
    partyProfileId: java.util.UUID?,
): ChangeActor = when (type) {
    GeneratedLimitChangeActorType.SYSTEM -> ChangeActor.System
    GeneratedLimitChangeActorType.PARTY_PROFILE ->
        ChangeActor.PartyProfileActor(PartyProfileId.of(requireNotNull(partyProfileId)))
}

private fun LimitRuleChangeEventType.toGenerated() = GeneratedLimitRuleChangeEventType.valueOf(name)

private fun LimitRuleChangeStatus.toGenerated() = GeneratedLimitRuleChangeStatus.valueOf(name)

private fun LimitRuleRevisionEventType.toGenerated() = GeneratedLimitRuleRevisionEventType.valueOf(name)

private fun Instant.toOffsetDateTime(): OffsetDateTime = OffsetDateTime.ofInstant(this, ZoneOffset.UTC)
