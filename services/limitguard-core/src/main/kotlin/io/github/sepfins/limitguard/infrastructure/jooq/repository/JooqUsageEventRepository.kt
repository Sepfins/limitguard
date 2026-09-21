package io.github.sepfins.limitguard.infrastructure.jooq.repository

import io.github.sepfins.limitguard.domain.common.CurrencyCode
import io.github.sepfins.limitguard.domain.common.LimitRuleId
import io.github.sepfins.limitguard.domain.common.PartyProfileId
import io.github.sepfins.limitguard.domain.common.TenantId
import io.github.sepfins.limitguard.domain.common.TransactionEventId
import io.github.sepfins.limitguard.domain.common.UsageBucketId
import io.github.sepfins.limitguard.domain.common.UsageEventId
import io.github.sepfins.limitguard.domain.usage.LimitUsageEvent
import io.github.sepfins.limitguard.domain.usage.LimitUsageEventType
import io.github.sepfins.limitguard.infrastructure.jooq.JooqPersistenceContext
import io.github.sepfins.limitguard.infrastructure.jooq.generated.enums.LimitUsageEventType as GeneratedLimitUsageEventType
import io.github.sepfins.limitguard.infrastructure.jooq.generated.tables.LimitUsageEvent as GeneratedLimitUsageEventTable
import io.github.sepfins.limitguard.infrastructure.jooq.jooqOperation
import io.github.sepfins.limitguard.infrastructure.jooq.toJsonb
import io.github.sepfins.limitguard.infrastructure.jooq.toMetadata
import io.github.sepfins.limitguard.port.persistence.UsageEventRepository
import org.jooq.Record
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

class JooqUsageEventRepository(
    private val context: JooqPersistenceContext,
) : UsageEventRepository {
    private val table = GeneratedLimitUsageEventTable.LIMIT_USAGE_EVENT

    override fun findById(tenantId: TenantId, id: UsageEventId): LimitUsageEvent? =
        jooqOperation("find usage event by id") {
            selectEvents()
                .where(table.TENANT_ID.eq(tenantId.value))
                .and(table.ID.eq(id.value))
                .fetchOne { it.toDomain() }
        }

    override fun findByBucket(tenantId: TenantId, bucketId: UsageBucketId): List<LimitUsageEvent> =
        jooqOperation("find usage events by bucket") {
            selectEvents()
                .where(table.TENANT_ID.eq(tenantId.value))
                .and(table.USAGE_BUCKET_ID.eq(bucketId.value))
                .orderBy(table.CREATED_AT, table.ID)
                .fetch { it.toDomain() }
        }

    override fun findByTransactionEvent(
        tenantId: TenantId,
        transactionEventId: TransactionEventId,
    ): List<LimitUsageEvent> = jooqOperation("find usage events by transaction event") {
        selectEvents()
            .where(table.TENANT_ID.eq(tenantId.value))
            .and(table.TRANSACTION_EVENT_ID.eq(transactionEventId.value))
            .orderBy(table.CREATED_AT, table.ID)
            .fetch { it.toDomain() }
    }

    override fun findByTransactionEventAndType(
        tenantId: TenantId,
        transactionEventId: TransactionEventId,
        type: LimitUsageEventType,
    ): List<LimitUsageEvent> = jooqOperation("find usage events by transaction event and type") {
        selectEvents()
            .where(table.TENANT_ID.eq(tenantId.value))
            .and(table.TRANSACTION_EVENT_ID.eq(transactionEventId.value))
            .and(table.EVENT_TYPE.eq(GeneratedLimitUsageEventType.valueOf(type.name)))
            .orderBy(table.CREATED_AT, table.ID)
            .fetch { it.toDomain() }
    }

    override fun findByLimitRuleAndTransactionEvent(
        tenantId: TenantId,
        limitRuleId: LimitRuleId,
        transactionEventId: TransactionEventId,
    ): List<LimitUsageEvent> = jooqOperation("find usage events by rule and transaction event") {
        selectEvents()
            .where(table.TENANT_ID.eq(tenantId.value))
            .and(table.LIMIT_RULE_ID.eq(limitRuleId.value))
            .and(table.TRANSACTION_EVENT_ID.eq(transactionEventId.value))
            .orderBy(table.CREATED_AT, table.ID)
            .fetch { it.toDomain() }
    }

    override fun findByLimitRuleTransactionEventAndType(
        tenantId: TenantId,
        limitRuleId: LimitRuleId,
        transactionEventId: TransactionEventId,
        type: LimitUsageEventType,
    ): LimitUsageEvent? = jooqOperation("find usage event by rule, transaction event, and type") {
        selectEvents()
            .where(table.TENANT_ID.eq(tenantId.value))
            .and(table.LIMIT_RULE_ID.eq(limitRuleId.value))
            .and(table.TRANSACTION_EVENT_ID.eq(transactionEventId.value))
            .and(table.EVENT_TYPE.eq(GeneratedLimitUsageEventType.valueOf(type.name)))
            .fetchOne { it.toDomain() }
    }

    override fun insert(tenantId: TenantId, event: LimitUsageEvent): LimitUsageEvent =
        jooqOperation("insert usage event") {
            require(event.tenantId == tenantId) { "usage event tenant does not match repository tenant" }
            val insert = context.dsl()
                .insertInto(table)
                .set(table.ID, event.id.value)
                .set(table.TENANT_ID, tenantId.value)
                .set(table.LIMIT_RULE_ID, event.limitRuleId.value)
                .set(table.USAGE_BUCKET_ID, event.usageBucketId?.value)
                .set(table.TRANSACTION_EVENT_ID, event.transactionEventId.value)
                .set(table.ASSIGNEE_PARTY_PROFILE_ID, event.assigneePartyProfileId.value)
                .set(table.EVENT_TYPE, GeneratedLimitUsageEventType.valueOf(event.eventType.name))
                .set(table.AMOUNT_DELTA, event.amountDelta)
                .set(table.CURRENCY, event.currency.value)
                .set(table.METADATA, event.metadata.toJsonb())
            event.createdAt?.let { insert.set(table.CREATED_AT, it.toOffsetDateTime()) }
            requireNotNull(insert.returning(table.fields().toList()).fetchOne()).toDomain()
        }

    private fun selectEvents() = context.dsl().select(
        table.ID,
        table.TENANT_ID,
        table.LIMIT_RULE_ID,
        table.USAGE_BUCKET_ID,
        table.TRANSACTION_EVENT_ID,
        table.ASSIGNEE_PARTY_PROFILE_ID,
        table.EVENT_TYPE,
        table.AMOUNT_DELTA,
        table.CURRENCY,
        table.METADATA,
        table.CREATED_AT,
    ).from(table)

    private fun Record.toDomain(): LimitUsageEvent = LimitUsageEvent(
        id = UsageEventId.of(requireNotNull(get(table.ID))),
        tenantId = TenantId.of(requireNotNull(get(table.TENANT_ID))),
        limitRuleId = LimitRuleId.of(requireNotNull(get(table.LIMIT_RULE_ID))),
        usageBucketId = get(table.USAGE_BUCKET_ID)?.let(UsageBucketId::of),
        transactionEventId = TransactionEventId.of(requireNotNull(get(table.TRANSACTION_EVENT_ID))),
        assigneePartyProfileId = PartyProfileId.of(
            requireNotNull(get(table.ASSIGNEE_PARTY_PROFILE_ID))
        ),
        eventType = LimitUsageEventType.valueOf(requireNotNull(get(table.EVENT_TYPE)).name),
        amountDelta = requireNotNull(get(table.AMOUNT_DELTA)),
        currency = CurrencyCode.of(requireNotNull(get(table.CURRENCY))),
        metadata = get(table.METADATA).toMetadata(),
        createdAt = get(table.CREATED_AT)?.toInstant(),
    )

    private fun Instant.toOffsetDateTime(): OffsetDateTime = OffsetDateTime.ofInstant(this, ZoneOffset.UTC)
}
