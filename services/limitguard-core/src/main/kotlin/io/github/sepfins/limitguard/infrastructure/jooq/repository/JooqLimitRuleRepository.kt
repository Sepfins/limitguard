package io.github.sepfins.limitguard.infrastructure.jooq.repository

import io.github.sepfins.limitguard.domain.common.ChannelGroupId
import io.github.sepfins.limitguard.domain.common.ChannelId
import io.github.sepfins.limitguard.domain.common.CurrencyCode
import io.github.sepfins.limitguard.domain.common.EffectiveWindow
import io.github.sepfins.limitguard.domain.common.LimitConfigurationId
import io.github.sepfins.limitguard.domain.common.LimitRuleId
import io.github.sepfins.limitguard.domain.common.MandateId
import io.github.sepfins.limitguard.domain.common.Money
import io.github.sepfins.limitguard.domain.common.OperationTypeId
import io.github.sepfins.limitguard.domain.common.PartyProfileId
import io.github.sepfins.limitguard.domain.common.TenantId
import io.github.sepfins.limitguard.domain.common.Version
import io.github.sepfins.limitguard.domain.limit.LimitChannelScope
import io.github.sepfins.limitguard.domain.limit.LimitKind
import io.github.sepfins.limitguard.domain.limit.LimitOperationScope
import io.github.sepfins.limitguard.domain.limit.LimitPeriod
import io.github.sepfins.limitguard.domain.limit.LimitPriority
import io.github.sepfins.limitguard.domain.limit.LimitRule
import io.github.sepfins.limitguard.domain.limit.LimitRuleName
import io.github.sepfins.limitguard.domain.limit.LimitUsageConsumptionMode
import io.github.sepfins.limitguard.domain.mandate.MandateScope
import io.github.sepfins.limitguard.infrastructure.jooq.JooqPersistenceContext
import io.github.sepfins.limitguard.infrastructure.jooq.jooqOperation
import io.github.sepfins.limitguard.infrastructure.jooq.nullablePostgresEnum
import io.github.sepfins.limitguard.infrastructure.jooq.postgresEnum
import io.github.sepfins.limitguard.infrastructure.jooq.toJsonb
import io.github.sepfins.limitguard.infrastructure.jooq.toMetadata
import io.github.sepfins.limitguard.port.persistence.LimitRuleRepository
import org.jooq.Field
import org.jooq.JSONB
import org.jooq.Record
import org.jooq.SelectFieldOrAsterisk
import org.jooq.Table
import org.jooq.impl.DSL
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

class JooqLimitRuleRepository(
    private val context: JooqPersistenceContext,
) : LimitRuleRepository {
    override fun findById(tenantId: TenantId, id: LimitRuleId): LimitRule? =
        findById(tenantId, id, forUpdate = false)

    override fun findByIdForUpdate(tenantId: TenantId, id: LimitRuleId): LimitRule? {
        context.requireTransaction("locking a limit rule row")
        return findById(tenantId, id, forUpdate = true)
    }

    override fun findActiveForOwner(
        tenantId: TenantId,
        owner: PartyProfileId,
        at: Instant,
    ): List<LimitRule> = jooqOperation("find active limit rules for owner") {
        selectRules()
            .where(LimitRuleTable.tenantId.eq(tenantId.value))
            .and(LimitRuleTable.ownerPartyProfileId.eq(owner.value))
            .and(LimitRuleTable.isActive.isTrue)
            .and(LimitRuleTable.startsAt.le(at))
            .and(LimitRuleTable.endsAt.isNull.or(LimitRuleTable.endsAt.gt(at)))
            .fetch { it.toDomain() }
    }

    override fun insert(tenantId: TenantId, limitRule: LimitRule): LimitRule =
        jooqOperation("insert limit rule") {
            require(limitRule.tenantId == tenantId) { "limit rule tenant does not match repository tenant" }
            val insert = context.dsl()
                .insertInto(LimitRuleTable.table)
                .set(LimitRuleTable.id, limitRule.id.value)
                .set(LimitRuleTable.tenantId, tenantId.value)
                .set(LimitRuleTable.ownerPartyProfileId, limitRule.ownerPartyProfileId.value)
                .set(LimitRuleTable.name, limitRule.name.value)
                .set(LimitRuleTable.description, limitRule.description)
                .set(LimitRuleTable.limitKind, postgresEnum(limitRule.kindName(), "limit_kind"))
                .set(LimitRuleTable.period, nullablePostgresEnum(limitRule.periodName(), "limit_period"))
                .set(LimitRuleTable.amount, limitRule.amount.amount)
                .set(LimitRuleTable.currency, limitRule.amount.currency.value)
                .set(LimitRuleTable.mandateScope, postgresEnum(limitRule.mandateScopeName(), "limit_mandate_scope"))
                .set(LimitRuleTable.mandateId, limitRule.mandateId())
                .set(LimitRuleTable.configurationId, limitRule.configurationId?.value)
                .set(LimitRuleTable.channelScope, postgresEnum(limitRule.channelScopeName(), "limit_channel_scope"))
                .set(LimitRuleTable.channelId, limitRule.channelId())
                .set(LimitRuleTable.channelGroupId, limitRule.channelGroupId())
                .set(LimitRuleTable.operationTypeId, limitRule.operationTypeId())
                .set(
                    LimitRuleTable.usageConsumptionMode,
                    postgresEnum(limitRule.usageConsumptionMode.name, "limit_usage_consumption_mode"),
                )
                .set(LimitRuleTable.priority, limitRule.priority.value)
                .set(LimitRuleTable.isActive, limitRule.isActive)
                .set(LimitRuleTable.version, limitRule.version.value)
                .set(LimitRuleTable.startsAt, limitRule.effectiveWindow.startsAt)
                .set(LimitRuleTable.endsAt, limitRule.effectiveWindow.endsAt)
                .set(LimitRuleTable.metadata, limitRule.metadata.toJsonb())
            limitRule.createdAt?.let { insert.set(LimitRuleTable.createdAt, it) }
            limitRule.updatedAt?.let { insert.set(LimitRuleTable.updatedAt, it) }
            requireNotNull(insert.returning(LimitRuleTable.fields).fetchOne()).toDomain()
        }

    override fun updateIfVersion(
        tenantId: TenantId,
        limitRule: LimitRule,
        expectedVersion: Version,
    ): LimitRule? = jooqOperation("conditionally update limit rule") {
        require(limitRule.tenantId == tenantId) { "limit rule tenant does not match repository tenant" }
        val update = context.dsl()
            .update(LimitRuleTable.table)
            .set(LimitRuleTable.ownerPartyProfileId, limitRule.ownerPartyProfileId.value)
            .set(LimitRuleTable.name, limitRule.name.value)
            .set(LimitRuleTable.description, limitRule.description)
            .set(LimitRuleTable.limitKind, postgresEnum(limitRule.kindName(), "limit_kind"))
            .set(LimitRuleTable.period, nullablePostgresEnum(limitRule.periodName(), "limit_period"))
            .set(LimitRuleTable.amount, limitRule.amount.amount)
            .set(LimitRuleTable.currency, limitRule.amount.currency.value)
            .set(LimitRuleTable.mandateScope, postgresEnum(limitRule.mandateScopeName(), "limit_mandate_scope"))
            .set(LimitRuleTable.mandateId, limitRule.mandateId())
            .set(LimitRuleTable.configurationId, limitRule.configurationId?.value)
            .set(LimitRuleTable.channelScope, postgresEnum(limitRule.channelScopeName(), "limit_channel_scope"))
            .set(LimitRuleTable.channelId, limitRule.channelId())
            .set(LimitRuleTable.channelGroupId, limitRule.channelGroupId())
            .set(LimitRuleTable.operationTypeId, limitRule.operationTypeId())
            .set(
                LimitRuleTable.usageConsumptionMode,
                postgresEnum(limitRule.usageConsumptionMode.name, "limit_usage_consumption_mode"),
            )
            .set(LimitRuleTable.priority, limitRule.priority.value)
            .set(LimitRuleTable.isActive, limitRule.isActive)
            .set(LimitRuleTable.version, limitRule.version.value)
            .set(LimitRuleTable.startsAt, limitRule.effectiveWindow.startsAt)
            .set(LimitRuleTable.endsAt, limitRule.effectiveWindow.endsAt)
            .set(LimitRuleTable.metadata, limitRule.metadata.toJsonb())
        limitRule.updatedAt?.let { update.set(LimitRuleTable.updatedAt, it) }
        update.where(LimitRuleTable.tenantId.eq(tenantId.value))
            .and(LimitRuleTable.id.eq(limitRule.id.value))
            .and(LimitRuleTable.version.eq(expectedVersion.value))
            .returning(LimitRuleTable.fields)
            .fetchOne()
            ?.toDomain()
    }

    private fun findById(
        tenantId: TenantId,
        id: LimitRuleId,
        forUpdate: Boolean,
    ): LimitRule? = jooqOperation("find limit rule by id") {
        val query = selectRules()
            .where(LimitRuleTable.tenantId.eq(tenantId.value))
            .and(LimitRuleTable.id.eq(id.value))
        if (forUpdate) query.forUpdate().fetchOne { it.toDomain() } else query.fetchOne { it.toDomain() }
    }

    private fun selectRules() = context.dsl()
        .select(LimitRuleTable.fields)
        .from(LimitRuleTable.table)

    private fun Record.toDomain(): LimitRule {
        val mandateScope = when (val value = requireNotNull(get(LimitRuleTable.mandateScope))) {
            "ALL_OWNER_MANDATES" -> MandateScope.AllOwnerMandates
            "MANDATE" -> MandateScope.MandateSpecific(MandateId.of(requireNotNull(get(LimitRuleTable.mandateId))))
            else -> error("unsupported limit mandate scope: $value")
        }
        val channelScope = when (val value = requireNotNull(get(LimitRuleTable.channelScope))) {
            "ALL_CHANNELS" -> LimitChannelScope.AllChannels
            "CHANNEL" -> LimitChannelScope.ChannelSpecific(ChannelId.of(requireNotNull(get(LimitRuleTable.channelId))))
            "CHANNEL_GROUP" -> LimitChannelScope.ChannelGroupSpecific(
                ChannelGroupId.of(requireNotNull(get(LimitRuleTable.channelGroupId)))
            )
            else -> error("unsupported limit channel scope: $value")
        }
        val operationScope = get(LimitRuleTable.operationTypeId)?.let {
            LimitOperationScope.OperationTypeSpecific(OperationTypeId.of(it))
        } ?: LimitOperationScope.AllOperations
        val limitKind = when (val value = requireNotNull(get(LimitRuleTable.limitKind))) {
            "PER_TRANSACTION" -> LimitKind.PerTransaction
            "PERIODIC" -> LimitKind.Periodic(
                LimitPeriod.valueOf(requireNotNull(get(LimitRuleTable.period)))
            )
            else -> error("unsupported limit kind: $value")
        }
        val currency = CurrencyCode.of(requireNotNull(get(LimitRuleTable.currency)))

        return LimitRule(
            id = LimitRuleId.of(requireNotNull(get(LimitRuleTable.id))),
            tenantId = TenantId.of(requireNotNull(get(LimitRuleTable.tenantId))),
            ownerPartyProfileId = PartyProfileId.of(requireNotNull(get(LimitRuleTable.ownerPartyProfileId))),
            name = LimitRuleName.of(requireNotNull(get(LimitRuleTable.name))),
            description = get(LimitRuleTable.description),
            limitKind = limitKind,
            amount = Money.positive(requireNotNull(get(LimitRuleTable.amount)), currency),
            mandateScope = mandateScope,
            configurationId = get(LimitRuleTable.configurationId)?.let(LimitConfigurationId::of),
            channelScope = channelScope,
            operationScope = operationScope,
            usageConsumptionMode = LimitUsageConsumptionMode.valueOf(
                requireNotNull(get(LimitRuleTable.usageConsumptionMode))
            ),
            priority = LimitPriority.of(requireNotNull(get(LimitRuleTable.priority))),
            isActive = requireNotNull(get(LimitRuleTable.isActive)),
            version = Version.of(requireNotNull(get(LimitRuleTable.version))),
            effectiveWindow = EffectiveWindow(
                startsAt = requireNotNull(get(LimitRuleTable.startsAt)),
                endsAt = get(LimitRuleTable.endsAt),
            ),
            metadata = get(LimitRuleTable.metadata).toMetadata(),
            createdAt = get(LimitRuleTable.createdAt),
            updatedAt = get(LimitRuleTable.updatedAt),
        )
    }

    private fun LimitRule.kindName(): String = when (limitKind) {
        LimitKind.PerTransaction -> "PER_TRANSACTION"
        is LimitKind.Periodic -> "PERIODIC"
    }

    private fun LimitRule.periodName(): String? = (limitKind as? LimitKind.Periodic)?.period?.name

    private fun LimitRule.mandateScopeName(): String = when (mandateScope) {
        MandateScope.AllOwnerMandates -> "ALL_OWNER_MANDATES"
        is MandateScope.MandateSpecific -> "MANDATE"
    }

    private fun LimitRule.mandateId(): UUID? = (mandateScope as? MandateScope.MandateSpecific)?.mandateId?.value

    private fun LimitRule.channelScopeName(): String = when (channelScope) {
        LimitChannelScope.AllChannels -> "ALL_CHANNELS"
        is LimitChannelScope.ChannelSpecific -> "CHANNEL"
        is LimitChannelScope.ChannelGroupSpecific -> "CHANNEL_GROUP"
    }

    private fun LimitRule.channelId(): UUID? =
        (channelScope as? LimitChannelScope.ChannelSpecific)?.channelId?.value

    private fun LimitRule.channelGroupId(): UUID? =
        (channelScope as? LimitChannelScope.ChannelGroupSpecific)?.channelGroupId?.value

    private fun LimitRule.operationTypeId(): UUID? =
        (operationScope as? LimitOperationScope.OperationTypeSpecific)?.operationTypeId?.value
}

private object LimitRuleTable {
    val table: Table<Record> = DSL.table(DSL.name("limit_rule"))
    val id: Field<UUID> = field("id", UUID::class.java)
    val tenantId: Field<UUID> = field("tenant_id", UUID::class.java)
    val ownerPartyProfileId: Field<UUID> = field("owner_party_profile_id", UUID::class.java)
    val name: Field<String> = field("name", String::class.java)
    val description: Field<String> = field("description", String::class.java)
    val limitKind: Field<String> = field("limit_kind", String::class.java)
    val period: Field<String> = field("period", String::class.java)
    val amount: Field<BigDecimal> = field("amount", BigDecimal::class.java)
    val currency: Field<String> = field("currency", String::class.java)
    val mandateScope: Field<String> = field("mandate_scope", String::class.java)
    val mandateId: Field<UUID> = field("mandate_id", UUID::class.java)
    val configurationId: Field<UUID> = field("configuration_id", UUID::class.java)
    val channelScope: Field<String> = field("channel_scope", String::class.java)
    val channelId: Field<UUID> = field("channel_id", UUID::class.java)
    val channelGroupId: Field<UUID> = field("channel_group_id", UUID::class.java)
    val operationTypeId: Field<UUID> = field("operation_type_id", UUID::class.java)
    val usageConsumptionMode: Field<String> = field("usage_consumption_mode", String::class.java)
    val priority: Field<Int> = field("priority", Int::class.java)
    val isActive: Field<Boolean> = field("is_active", Boolean::class.java)
    val version: Field<Int> = field("version", Int::class.java)
    val startsAt: Field<Instant> = field("starts_at", Instant::class.java)
    val endsAt: Field<Instant> = field("ends_at", Instant::class.java)
    val metadata: Field<JSONB> = field("metadata", JSONB::class.java)
    val createdAt: Field<Instant> = field("created_at", Instant::class.java)
    val updatedAt: Field<Instant> = field("updated_at", Instant::class.java)
    val fields: List<SelectFieldOrAsterisk> = listOf(
        id,
        tenantId,
        ownerPartyProfileId,
        name,
        description,
        limitKind,
        period,
        amount,
        currency,
        mandateScope,
        mandateId,
        configurationId,
        channelScope,
        channelId,
        channelGroupId,
        operationTypeId,
        usageConsumptionMode,
        priority,
        isActive,
        version,
        startsAt,
        endsAt,
        metadata,
        createdAt,
        updatedAt,
    )

    private fun <T> field(name: String, type: Class<T>): Field<T> =
        DSL.field(DSL.name("limit_rule", name), type)
}
