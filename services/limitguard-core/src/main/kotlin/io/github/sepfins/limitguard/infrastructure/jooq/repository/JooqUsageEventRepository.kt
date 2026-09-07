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
import io.github.sepfins.limitguard.infrastructure.jooq.eqPostgresEnum
import io.github.sepfins.limitguard.infrastructure.jooq.jooqField
import io.github.sepfins.limitguard.infrastructure.jooq.jooqOperation
import io.github.sepfins.limitguard.infrastructure.jooq.jooqTable
import io.github.sepfins.limitguard.infrastructure.jooq.postgresEnum
import io.github.sepfins.limitguard.infrastructure.jooq.toJsonb
import io.github.sepfins.limitguard.infrastructure.jooq.toMetadata
import io.github.sepfins.limitguard.port.persistence.UsageEventRepository
import org.jooq.Field
import org.jooq.JSONB
import org.jooq.Record
import org.jooq.SelectFieldOrAsterisk
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

class JooqUsageEventRepository(
    private val context: JooqPersistenceContext,
) : UsageEventRepository {
    override fun findById(tenantId: TenantId, id: UsageEventId): LimitUsageEvent? =
        jooqOperation("find usage event by id") {
            selectEvents()
                .where(UsageEventTable.tenantId.eq(tenantId.value))
                .and(UsageEventTable.id.eq(id.value))
                .fetchOne { it.toDomain() }
        }

    override fun findByBucket(tenantId: TenantId, bucketId: UsageBucketId): List<LimitUsageEvent> =
        jooqOperation("find usage events by bucket") {
            selectEvents()
                .where(UsageEventTable.tenantId.eq(tenantId.value))
                .and(UsageEventTable.usageBucketId.eq(bucketId.value))
                .orderBy(UsageEventTable.createdAt, UsageEventTable.id)
                .fetch { it.toDomain() }
        }

    override fun findByTransactionEvent(
        tenantId: TenantId,
        transactionEventId: TransactionEventId,
    ): List<LimitUsageEvent> = jooqOperation("find usage events by transaction event") {
        selectEvents()
            .where(UsageEventTable.tenantId.eq(tenantId.value))
            .and(UsageEventTable.transactionEventId.eq(transactionEventId.value))
            .orderBy(UsageEventTable.createdAt, UsageEventTable.id)
            .fetch { it.toDomain() }
    }

    override fun findByTransactionEventAndType(
        tenantId: TenantId,
        transactionEventId: TransactionEventId,
        type: LimitUsageEventType,
    ): List<LimitUsageEvent> = jooqOperation("find usage events by transaction event and type") {
        selectEvents()
            .where(UsageEventTable.tenantId.eq(tenantId.value))
            .and(UsageEventTable.transactionEventId.eq(transactionEventId.value))
            .and(UsageEventTable.eventType.eqPostgresEnum(type.name, "limit_usage_event_type"))
            .orderBy(UsageEventTable.createdAt, UsageEventTable.id)
            .fetch { it.toDomain() }
    }

    override fun findByLimitRuleAndTransactionEvent(
        tenantId: TenantId,
        limitRuleId: LimitRuleId,
        transactionEventId: TransactionEventId,
    ): List<LimitUsageEvent> = jooqOperation("find usage events by rule and transaction event") {
        selectEvents()
            .where(UsageEventTable.tenantId.eq(tenantId.value))
            .and(UsageEventTable.limitRuleId.eq(limitRuleId.value))
            .and(UsageEventTable.transactionEventId.eq(transactionEventId.value))
            .orderBy(UsageEventTable.createdAt, UsageEventTable.id)
            .fetch { it.toDomain() }
    }

    override fun findByLimitRuleTransactionEventAndType(
        tenantId: TenantId,
        limitRuleId: LimitRuleId,
        transactionEventId: TransactionEventId,
        type: LimitUsageEventType,
    ): LimitUsageEvent? = jooqOperation("find usage event by rule, transaction event, and type") {
        selectEvents()
            .where(UsageEventTable.tenantId.eq(tenantId.value))
            .and(UsageEventTable.limitRuleId.eq(limitRuleId.value))
            .and(UsageEventTable.transactionEventId.eq(transactionEventId.value))
            .and(UsageEventTable.eventType.eqPostgresEnum(type.name, "limit_usage_event_type"))
            .fetchOne { it.toDomain() }
    }

    override fun insert(tenantId: TenantId, event: LimitUsageEvent): LimitUsageEvent =
        jooqOperation("insert usage event") {
            require(event.tenantId == tenantId) { "usage event tenant does not match repository tenant" }
            val insert = context.dsl()
                .insertInto(UsageEventTable.table)
                .set(UsageEventTable.id, event.id.value)
                .set(UsageEventTable.tenantId, tenantId.value)
                .set(UsageEventTable.limitRuleId, event.limitRuleId.value)
                .set(UsageEventTable.usageBucketId, event.usageBucketId?.value)
                .set(UsageEventTable.transactionEventId, event.transactionEventId.value)
                .set(UsageEventTable.assigneePartyProfileId, event.assigneePartyProfileId.value)
                .set(UsageEventTable.eventType, postgresEnum(event.eventType.name, "limit_usage_event_type"))
                .set(UsageEventTable.amountDelta, event.amountDelta)
                .set(UsageEventTable.currency, event.currency.value)
                .set(UsageEventTable.metadata, event.metadata.toJsonb())
            event.createdAt?.let { insert.set(UsageEventTable.createdAt, it) }
            requireNotNull(insert.returning(UsageEventTable.fields).fetchOne()).toDomain()
        }

    private fun selectEvents() = context.dsl().select(UsageEventTable.fields).from(UsageEventTable.table)

    private fun Record.toDomain(): LimitUsageEvent = LimitUsageEvent(
        id = UsageEventId.of(requireNotNull(get(UsageEventTable.id))),
        tenantId = TenantId.of(requireNotNull(get(UsageEventTable.tenantId))),
        limitRuleId = LimitRuleId.of(requireNotNull(get(UsageEventTable.limitRuleId))),
        usageBucketId = get(UsageEventTable.usageBucketId)?.let(UsageBucketId::of),
        transactionEventId = TransactionEventId.of(requireNotNull(get(UsageEventTable.transactionEventId))),
        assigneePartyProfileId = PartyProfileId.of(
            requireNotNull(get(UsageEventTable.assigneePartyProfileId))
        ),
        eventType = LimitUsageEventType.valueOf(requireNotNull(get(UsageEventTable.eventType))),
        amountDelta = requireNotNull(get(UsageEventTable.amountDelta)),
        currency = CurrencyCode.of(requireNotNull(get(UsageEventTable.currency))),
        metadata = get(UsageEventTable.metadata).toMetadata(),
        createdAt = get(UsageEventTable.createdAt),
    )
}

private object UsageEventTable {
    const val NAME = "limit_usage_event"
    val table = jooqTable(NAME)
    val id: Field<UUID> = jooqField(NAME, "id", UUID::class.java)
    val tenantId: Field<UUID> = jooqField(NAME, "tenant_id", UUID::class.java)
    val limitRuleId: Field<UUID> = jooqField(NAME, "limit_rule_id", UUID::class.java)
    val usageBucketId: Field<UUID> = jooqField(NAME, "usage_bucket_id", UUID::class.java)
    val transactionEventId: Field<UUID> = jooqField(NAME, "transaction_event_id", UUID::class.java)
    val assigneePartyProfileId: Field<UUID> = jooqField(NAME, "assignee_party_profile_id", UUID::class.java)
    val eventType: Field<String> = jooqField(NAME, "event_type", String::class.java)
    val amountDelta: Field<BigDecimal> = jooqField(NAME, "amount_delta", BigDecimal::class.java)
    val currency: Field<String> = jooqField(NAME, "currency", String::class.java)
    val metadata: Field<JSONB> = jooqField(NAME, "metadata", JSONB::class.java)
    val createdAt: Field<Instant> = jooqField(NAME, "created_at", Instant::class.java)
    val fields: List<SelectFieldOrAsterisk> = listOf(
        id,
        tenantId,
        limitRuleId,
        usageBucketId,
        transactionEventId,
        assigneePartyProfileId,
        eventType,
        amountDelta,
        currency,
        metadata,
        createdAt,
    )
}
