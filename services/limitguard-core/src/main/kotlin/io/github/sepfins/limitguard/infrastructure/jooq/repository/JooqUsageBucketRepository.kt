package io.github.sepfins.limitguard.infrastructure.jooq.repository

import io.github.sepfins.limitguard.domain.common.CurrencyCode
import io.github.sepfins.limitguard.domain.common.LimitRuleId
import io.github.sepfins.limitguard.domain.common.Money
import io.github.sepfins.limitguard.domain.common.PartyProfileId
import io.github.sepfins.limitguard.domain.common.TenantId
import io.github.sepfins.limitguard.domain.common.UsageBucketId
import io.github.sepfins.limitguard.domain.common.UsageWindow
import io.github.sepfins.limitguard.domain.usage.LimitUsageBucket
import io.github.sepfins.limitguard.infrastructure.jooq.JooqPersistenceContext
import io.github.sepfins.limitguard.infrastructure.jooq.jooqOperation
import io.github.sepfins.limitguard.port.persistence.UsageBucketRepository
import org.jooq.Field
import org.jooq.Record
import org.jooq.SelectFieldOrAsterisk
import org.jooq.Table
import org.jooq.impl.DSL
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

class JooqUsageBucketRepository(
    private val context: JooqPersistenceContext,
) : UsageBucketRepository {
    override fun findByLimitRuleInWindow(
        tenantId: TenantId,
        limitRuleId: LimitRuleId,
        window: UsageWindow,
    ): List<LimitUsageBucket> = jooqOperation("find usage buckets by rule and window") {
        selectBuckets()
            .where(UsageBucketTable.tenantId.eq(tenantId.value))
            .and(UsageBucketTable.limitRuleId.eq(limitRuleId.value))
            .and(UsageBucketTable.periodStartsAt.eq(window.periodStartsAt))
            .and(UsageBucketTable.periodEndsAt.eq(window.periodEndsAt))
            .fetch { it.toDomain() }
    }

    override fun findByLimitRuleAndAssigneeInWindow(
        tenantId: TenantId,
        limitRuleId: LimitRuleId,
        assigneeId: PartyProfileId,
        currency: CurrencyCode,
        window: UsageWindow,
    ): LimitUsageBucket? = jooqOperation("find usage bucket by rule, assignee, currency, and window") {
        selectBuckets()
            .where(UsageBucketTable.tenantId.eq(tenantId.value))
            .and(UsageBucketTable.limitRuleId.eq(limitRuleId.value))
            .and(UsageBucketTable.assigneePartyProfileId.eq(assigneeId.value))
            .and(UsageBucketTable.currency.eq(currency.value))
            .and(UsageBucketTable.periodStartsAt.eq(window.periodStartsAt))
            .and(UsageBucketTable.periodEndsAt.eq(window.periodEndsAt))
            .fetchOne { it.toDomain() }
    }

    override fun findById(tenantId: TenantId, id: UsageBucketId): LimitUsageBucket? =
        findById(tenantId, id, forUpdate = false)

    override fun findByIdForUpdate(tenantId: TenantId, id: UsageBucketId): LimitUsageBucket? {
        context.requireTransaction("locking a usage bucket row")
        return findById(tenantId, id, forUpdate = true)
    }

    override fun findForUpdate(
        tenantId: TenantId,
        limitRuleId: LimitRuleId,
        assigneePartyProfileId: PartyProfileId,
        currency: CurrencyCode,
        window: UsageWindow,
    ): LimitUsageBucket? = jooqOperation("lock usage bucket key") {
        context.requireTransaction("locking a usage bucket key")
        val lockKey = listOf(
            "usage-bucket",
            tenantId.value,
            limitRuleId.value,
            assigneePartyProfileId.value,
            currency.value,
            window.periodStartsAt,
        ).joinToString(":")
        context.dsl().fetch("select pg_advisory_xact_lock(hashtextextended(?, 0))", lockKey)

        selectBuckets()
            .where(UsageBucketTable.tenantId.eq(tenantId.value))
            .and(UsageBucketTable.limitRuleId.eq(limitRuleId.value))
            .and(UsageBucketTable.assigneePartyProfileId.eq(assigneePartyProfileId.value))
            .and(UsageBucketTable.currency.eq(currency.value))
            .and(UsageBucketTable.periodStartsAt.eq(window.periodStartsAt))
            .forUpdate()
            .fetchOne { it.toDomain() }
    }

    override fun insert(tenantId: TenantId, usageBucket: LimitUsageBucket): LimitUsageBucket =
        jooqOperation("insert usage bucket") {
            require(usageBucket.tenantId == tenantId) { "usage bucket tenant does not match repository tenant" }
            val insert = context.dsl()
                .insertInto(UsageBucketTable.table)
                .set(UsageBucketTable.id, usageBucket.id.value)
                .set(UsageBucketTable.tenantId, tenantId.value)
                .set(UsageBucketTable.limitRuleId, usageBucket.limitRuleId.value)
                .set(UsageBucketTable.assigneePartyProfileId, usageBucket.assigneePartyProfileId.value)
                .set(UsageBucketTable.currency, usageBucket.currency.value)
                .set(UsageBucketTable.periodStartsAt, usageBucket.window.periodStartsAt)
                .set(UsageBucketTable.periodEndsAt, usageBucket.window.periodEndsAt)
                .set(UsageBucketTable.reservedAmount, usageBucket.reservedAmount.amount)
                .set(UsageBucketTable.consumedAmount, usageBucket.consumedAmount.amount)
            usageBucket.createdAt?.let { insert.set(UsageBucketTable.createdAt, it) }
            usageBucket.updatedAt?.let { insert.set(UsageBucketTable.updatedAt, it) }
            requireNotNull(insert.returning(UsageBucketTable.fields).fetchOne()).toDomain()
        }

    override fun update(tenantId: TenantId, usageBucket: LimitUsageBucket): LimitUsageBucket =
        jooqOperation("update usage bucket") {
            require(usageBucket.tenantId == tenantId) { "usage bucket tenant does not match repository tenant" }
            val update = context.dsl()
                .update(UsageBucketTable.table)
                .set(UsageBucketTable.periodStartsAt, usageBucket.window.periodStartsAt)
                .set(UsageBucketTable.periodEndsAt, usageBucket.window.periodEndsAt)
                .set(UsageBucketTable.reservedAmount, usageBucket.reservedAmount.amount)
                .set(UsageBucketTable.consumedAmount, usageBucket.consumedAmount.amount)
            usageBucket.updatedAt?.let { update.set(UsageBucketTable.updatedAt, it) }
            requireNotNull(
                update.where(UsageBucketTable.tenantId.eq(tenantId.value))
                    .and(UsageBucketTable.id.eq(usageBucket.id.value))
                    .returning(UsageBucketTable.fields)
                    .fetchOne()
            ) { "usage bucket not found: ${usageBucket.id.value}" }.toDomain()
        }

    private fun findById(
        tenantId: TenantId,
        id: UsageBucketId,
        forUpdate: Boolean,
    ): LimitUsageBucket? = jooqOperation("find usage bucket by id") {
        val query = selectBuckets()
            .where(UsageBucketTable.tenantId.eq(tenantId.value))
            .and(UsageBucketTable.id.eq(id.value))
        if (forUpdate) query.forUpdate().fetchOne { it.toDomain() } else query.fetchOne { it.toDomain() }
    }

    private fun selectBuckets() = context.dsl()
        .select(UsageBucketTable.fields)
        .from(UsageBucketTable.table)

    private fun Record.toDomain(): LimitUsageBucket {
        val currency = CurrencyCode.of(requireNotNull(get(UsageBucketTable.currency)))
        return LimitUsageBucket(
            id = UsageBucketId.of(requireNotNull(get(UsageBucketTable.id))),
            tenantId = TenantId.of(requireNotNull(get(UsageBucketTable.tenantId))),
            limitRuleId = LimitRuleId.of(requireNotNull(get(UsageBucketTable.limitRuleId))),
            assigneePartyProfileId = PartyProfileId.of(requireNotNull(get(UsageBucketTable.assigneePartyProfileId))),
            window = UsageWindow(
                periodStartsAt = requireNotNull(get(UsageBucketTable.periodStartsAt)),
                periodEndsAt = requireNotNull(get(UsageBucketTable.periodEndsAt)),
            ),
            reservedAmount = Money.of(requireNotNull(get(UsageBucketTable.reservedAmount)), currency),
            consumedAmount = Money.of(requireNotNull(get(UsageBucketTable.consumedAmount)), currency),
            createdAt = get(UsageBucketTable.createdAt),
            updatedAt = get(UsageBucketTable.updatedAt),
        )
    }
}

private object UsageBucketTable {
    val table: Table<Record> = DSL.table(DSL.name("limit_usage_bucket"))
    val id: Field<UUID> = DSL.field(DSL.name("limit_usage_bucket", "id"), UUID::class.java)
    val tenantId: Field<UUID> = DSL.field(DSL.name("limit_usage_bucket", "tenant_id"), UUID::class.java)
    val limitRuleId: Field<UUID> = DSL.field(DSL.name("limit_usage_bucket", "limit_rule_id"), UUID::class.java)
    val assigneePartyProfileId: Field<UUID> =
        DSL.field(DSL.name("limit_usage_bucket", "assignee_party_profile_id"), UUID::class.java)
    val currency: Field<String> = DSL.field(DSL.name("limit_usage_bucket", "currency"), String::class.java)
    val periodStartsAt: Field<Instant> =
        DSL.field(DSL.name("limit_usage_bucket", "period_started_at"), Instant::class.java)
    val periodEndsAt: Field<Instant> =
        DSL.field(DSL.name("limit_usage_bucket", "period_ended_at"), Instant::class.java)
    val reservedAmount: Field<BigDecimal> =
        DSL.field(DSL.name("limit_usage_bucket", "reserved_amount"), BigDecimal::class.java)
    val consumedAmount: Field<BigDecimal> =
        DSL.field(DSL.name("limit_usage_bucket", "consumed_amount"), BigDecimal::class.java)
    val createdAt: Field<Instant> = DSL.field(DSL.name("limit_usage_bucket", "created_at"), Instant::class.java)
    val updatedAt: Field<Instant> = DSL.field(DSL.name("limit_usage_bucket", "updated_at"), Instant::class.java)
    val fields: List<SelectFieldOrAsterisk> = listOf(
        id,
        tenantId,
        limitRuleId,
        assigneePartyProfileId,
        currency,
        periodStartsAt,
        periodEndsAt,
        reservedAmount,
        consumedAmount,
        createdAt,
        updatedAt,
    )
}
