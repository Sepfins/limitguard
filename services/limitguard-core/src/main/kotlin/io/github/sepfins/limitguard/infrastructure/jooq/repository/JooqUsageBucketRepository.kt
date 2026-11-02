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
import io.github.sepfins.limitguard.infrastructure.jooq.generated.tables.LimitUsageBucket as GeneratedLimitUsageBucketTable
import io.github.sepfins.limitguard.infrastructure.jooq.jooqOperation
import io.github.sepfins.limitguard.port.persistence.UsageBucketRepository
import org.jooq.Record
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

class JooqUsageBucketRepository(
    private val context: JooqPersistenceContext,
) : UsageBucketRepository {
    private val table = GeneratedLimitUsageBucketTable.LIMIT_USAGE_BUCKET

    override fun findByLimitRuleAndAssigneeInWindow(
        tenantId: TenantId,
        limitRuleId: LimitRuleId,
        assigneeId: PartyProfileId,
        currency: CurrencyCode,
        window: UsageWindow,
    ): LimitUsageBucket? = jooqOperation("find usage bucket by rule, assignee, currency, and window") {
        selectBuckets()
            .where(table.TENANT_ID.eq(tenantId.value))
            .and(table.LIMIT_RULE_ID.eq(limitRuleId.value))
            .and(table.ASSIGNEE_PARTY_PROFILE_ID.eq(assigneeId.value))
            .and(table.CURRENCY.eq(currency.value))
            .and(table.PERIOD_STARTED_AT.eq(window.periodStartsAt.toOffsetDateTime()))
            .and(table.PERIOD_ENDED_AT.eq(window.periodEndsAt.toOffsetDateTime()))
            .fetchOne { it.toDomain() }
    }

    override fun findById(tenantId: TenantId, id: UsageBucketId): LimitUsageBucket? =
        jooqOperation("find usage bucket by id") {
            selectBuckets()
                .where(table.TENANT_ID.eq(tenantId.value))
                .and(table.ID.eq(id.value))
                .fetchOne { it.toDomain() }
        }

    override fun findByIds(tenantId: TenantId, ids: Set<UsageBucketId>): List<LimitUsageBucket> =
        jooqOperation("find usage buckets by ids") {
            selectBuckets()
                .where(table.TENANT_ID.eq(tenantId.value))
                .and(table.ID.`in`(ids.map { it.value }))
                .fetch { it.toDomain() }
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
            .where(table.TENANT_ID.eq(tenantId.value))
            .and(table.LIMIT_RULE_ID.eq(limitRuleId.value))
            .and(table.ASSIGNEE_PARTY_PROFILE_ID.eq(assigneePartyProfileId.value))
            .and(table.CURRENCY.eq(currency.value))
            .and(table.PERIOD_STARTED_AT.eq(window.periodStartsAt.toOffsetDateTime()))
            .forUpdate()
            .fetchOne { it.toDomain() }
    }

    override fun insert(tenantId: TenantId, usageBucket: LimitUsageBucket): LimitUsageBucket =
        jooqOperation("insert usage bucket") {
            require(usageBucket.tenantId == tenantId) { "usage bucket tenant does not match repository tenant" }
            val insert = context.dsl()
                .insertInto(table)
                .set(table.ID, usageBucket.id.value)
                .set(table.TENANT_ID, tenantId.value)
                .set(table.LIMIT_RULE_ID, usageBucket.limitRuleId.value)
                .set(table.ASSIGNEE_PARTY_PROFILE_ID, usageBucket.assigneePartyProfileId.value)
                .set(table.CURRENCY, usageBucket.currency.value)
                .set(table.PERIOD_STARTED_AT, usageBucket.window.periodStartsAt.toOffsetDateTime())
                .set(table.PERIOD_ENDED_AT, usageBucket.window.periodEndsAt.toOffsetDateTime())
                .set(table.RESERVED_AMOUNT, usageBucket.reservedAmount.amount)
                .set(table.CONSUMED_AMOUNT, usageBucket.consumedAmount.amount)
            usageBucket.createdAt?.let { insert.set(table.CREATED_AT, it.toOffsetDateTime()) }
            usageBucket.updatedAt?.let { insert.set(table.UPDATED_AT, it.toOffsetDateTime()) }
            requireNotNull(insert.returning(table.fields().toList()).fetchOne()).toDomain()
        }

    override fun update(tenantId: TenantId, usageBucket: LimitUsageBucket): LimitUsageBucket =
        jooqOperation("update usage bucket") {
            require(usageBucket.tenantId == tenantId) { "usage bucket tenant does not match repository tenant" }
            val update = context.dsl()
                .update(table)
                .set(table.PERIOD_STARTED_AT, usageBucket.window.periodStartsAt.toOffsetDateTime())
                .set(table.PERIOD_ENDED_AT, usageBucket.window.periodEndsAt.toOffsetDateTime())
                .set(table.RESERVED_AMOUNT, usageBucket.reservedAmount.amount)
                .set(table.CONSUMED_AMOUNT, usageBucket.consumedAmount.amount)
            usageBucket.updatedAt?.let { update.set(table.UPDATED_AT, it.toOffsetDateTime()) }
            requireNotNull(
                update.where(table.TENANT_ID.eq(tenantId.value))
                    .and(table.ID.eq(usageBucket.id.value))
                    .returning(table.fields().toList())
                    .fetchOne()
            ) { "usage bucket not found: ${usageBucket.id.value}" }.toDomain()
        }

    private fun selectBuckets() = context.dsl()
        .select(
            table.ID,
            table.TENANT_ID,
            table.LIMIT_RULE_ID,
            table.ASSIGNEE_PARTY_PROFILE_ID,
            table.CURRENCY,
            table.PERIOD_STARTED_AT,
            table.PERIOD_ENDED_AT,
            table.RESERVED_AMOUNT,
            table.CONSUMED_AMOUNT,
            table.CREATED_AT,
            table.UPDATED_AT,
        )
        .from(table)

    private fun Record.toDomain(): LimitUsageBucket {
        val currency = CurrencyCode.of(requireNotNull(get(table.CURRENCY)))
        return LimitUsageBucket(
            id = UsageBucketId.of(requireNotNull(get(table.ID))),
            tenantId = TenantId.of(requireNotNull(get(table.TENANT_ID))),
            limitRuleId = LimitRuleId.of(requireNotNull(get(table.LIMIT_RULE_ID))),
            assigneePartyProfileId = PartyProfileId.of(requireNotNull(get(table.ASSIGNEE_PARTY_PROFILE_ID))),
            window = UsageWindow(
                periodStartsAt = requireNotNull(get(table.PERIOD_STARTED_AT)).toInstant(),
                periodEndsAt = requireNotNull(get(table.PERIOD_ENDED_AT)).toInstant(),
            ),
            reservedAmount = Money.of(requireNotNull(get(table.RESERVED_AMOUNT)), currency),
            consumedAmount = Money.of(requireNotNull(get(table.CONSUMED_AMOUNT)), currency),
            createdAt = get(table.CREATED_AT)?.toInstant(),
            updatedAt = get(table.UPDATED_AT)?.toInstant(),
        )
    }

    private fun Instant.toOffsetDateTime(): OffsetDateTime = OffsetDateTime.ofInstant(this, ZoneOffset.UTC)
}
