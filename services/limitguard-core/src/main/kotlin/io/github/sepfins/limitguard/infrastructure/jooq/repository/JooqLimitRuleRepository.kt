package io.github.sepfins.limitguard.infrastructure.jooq.repository

import io.github.sepfins.limitguard.domain.common.ChannelGroupId
import io.github.sepfins.limitguard.domain.common.ChannelId
import io.github.sepfins.limitguard.domain.common.CurrencyCode
import io.github.sepfins.limitguard.domain.common.EffectiveWindow
import io.github.sepfins.limitguard.domain.common.LimitConfigurationId
import io.github.sepfins.limitguard.domain.common.LimitRuleId
import io.github.sepfins.limitguard.domain.common.MandateId
import io.github.sepfins.limitguard.domain.limit.LimitAmount
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
import io.github.sepfins.limitguard.infrastructure.jooq.storedAmount
import io.github.sepfins.limitguard.infrastructure.jooq.storedLimitAmount
import io.github.sepfins.limitguard.infrastructure.jooq.toJsonb
import io.github.sepfins.limitguard.infrastructure.jooq.toMetadata
import io.github.sepfins.limitguard.infrastructure.jooq.generated.enums.LimitChannelScope as GeneratedLimitChannelScope
import io.github.sepfins.limitguard.infrastructure.jooq.generated.enums.LimitKind as GeneratedLimitKind
import io.github.sepfins.limitguard.infrastructure.jooq.generated.enums.LimitMandateScope as GeneratedLimitMandateScope
import io.github.sepfins.limitguard.infrastructure.jooq.generated.enums.LimitPeriod as GeneratedLimitPeriod
import io.github.sepfins.limitguard.infrastructure.jooq.generated.enums.LimitUsageConsumptionMode as GeneratedLimitUsageConsumptionMode
import io.github.sepfins.limitguard.infrastructure.jooq.generated.tables.LimitRule as GeneratedLimitRuleTable
import io.github.sepfins.limitguard.port.persistence.LimitRuleRepository
import org.jooq.Record
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

class JooqLimitRuleRepository(
    private val context: JooqPersistenceContext,
) : LimitRuleRepository {
    private val table = GeneratedLimitRuleTable.LIMIT_RULE

    override fun findById(tenantId: TenantId, id: LimitRuleId): LimitRule? =
        findById(tenantId, id, forUpdate = false)

    override fun findByIdForUpdate(tenantId: TenantId, id: LimitRuleId): LimitRule? {
        context.requireTransaction("locking a limit rule row")
        return findById(tenantId, id, forUpdate = true)
    }

    override fun findByOwner(
        tenantId: TenantId,
        owner: PartyProfileId,
    ): List<LimitRule> = jooqOperation("find limit rules for owner") {
        selectRules()
            .where(table.TENANT_ID.eq(tenantId.value))
            .and(table.OWNER_PARTY_PROFILE_ID.eq(owner.value))
            .orderBy(table.STARTS_AT, table.ID)
            .fetch { it.toDomain() }
    }

    override fun findActiveForOwner(
        tenantId: TenantId,
        owner: PartyProfileId,
        at: Instant,
    ): List<LimitRule> = jooqOperation("find active limit rules for owner") {
        val timestamp = at.toOffsetDateTime()
        selectRules()
            .where(table.TENANT_ID.eq(tenantId.value))
            .and(table.OWNER_PARTY_PROFILE_ID.eq(owner.value))
            .and(table.IS_ACTIVE.isTrue)
            .and(table.STARTS_AT.le(timestamp))
            .and(table.ENDS_AT.isNull.or(table.ENDS_AT.gt(timestamp)))
            .fetch { it.toDomain() }
    }

    override fun insert(tenantId: TenantId, limitRule: LimitRule): LimitRule =
        jooqOperation("insert limit rule") {
            require(limitRule.tenantId == tenantId) { "limit rule tenant does not match repository tenant" }
            val insert = context.dsl()
                .insertInto(table)
                .set(table.ID, limitRule.id.value)
                .set(table.TENANT_ID, tenantId.value)
                .set(table.OWNER_PARTY_PROFILE_ID, limitRule.ownerPartyProfileId.value)
                .set(table.NAME, limitRule.name.value)
                .set(table.DESCRIPTION, limitRule.description)
                .set(table.LIMIT_KIND, limitRule.limitKind.toGenerated())
                .set(table.PERIOD, limitRule.limitKind.period()?.toGenerated())
                .set(table.AMOUNT, limitRule.amount.storedAmount)
                .set(table.AMOUNT_UNLIMITED, limitRule.amount is LimitAmount.Unlimited)
                .set(table.CURRENCY, limitRule.amount.currency.value)
                .set(table.MANDATE_SCOPE, limitRule.mandateScope.toGenerated())
                .set(table.MANDATE_ID, limitRule.mandateId())
                .set(table.CONFIGURATION_ID, limitRule.configurationId.value)
                .set(table.CHANNEL_SCOPE, limitRule.channelScope.toGenerated())
                .set(table.CHANNEL_ID, limitRule.channelId())
                .set(table.CHANNEL_GROUP_ID, limitRule.channelGroupId())
                .set(table.OPERATION_TYPE_ID, limitRule.operationTypeId())
                .set(table.USAGE_CONSUMPTION_MODE, limitRule.usageConsumptionMode.toGenerated())
                .set(table.PRIORITY, limitRule.priority.value)
                .set(table.IS_ACTIVE, limitRule.isActive)
                .set(table.VERSION, limitRule.version.value)
                .set(table.STARTS_AT, limitRule.effectiveWindow.startsAt.toOffsetDateTime())
                .set(table.ENDS_AT, limitRule.effectiveWindow.endsAt?.toOffsetDateTime())
                .set(table.METADATA, limitRule.metadata.toJsonb())
            limitRule.createdAt?.let { insert.set(table.CREATED_AT, it.toOffsetDateTime()) }
            limitRule.updatedAt?.let { insert.set(table.UPDATED_AT, it.toOffsetDateTime()) }
            requireNotNull(insert.returning(table.fields().toList()).fetchOne()).toDomain()
        }

    override fun updateIfVersion(
        tenantId: TenantId,
        limitRule: LimitRule,
        expectedVersion: Version,
    ): LimitRule? = jooqOperation("conditionally update limit rule") {
        require(limitRule.tenantId == tenantId) { "limit rule tenant does not match repository tenant" }
        val update = context.dsl()
            .update(table)
            .set(table.OWNER_PARTY_PROFILE_ID, limitRule.ownerPartyProfileId.value)
            .set(table.NAME, limitRule.name.value)
            .set(table.DESCRIPTION, limitRule.description)
            .set(table.LIMIT_KIND, limitRule.limitKind.toGenerated())
            .set(table.PERIOD, limitRule.limitKind.period()?.toGenerated())
            .set(table.AMOUNT, limitRule.amount.storedAmount)
            .set(table.AMOUNT_UNLIMITED, limitRule.amount is LimitAmount.Unlimited)
            .set(table.CURRENCY, limitRule.amount.currency.value)
            .set(table.MANDATE_SCOPE, limitRule.mandateScope.toGenerated())
            .set(table.MANDATE_ID, limitRule.mandateId())
            .set(table.CONFIGURATION_ID, limitRule.configurationId.value)
            .set(table.CHANNEL_SCOPE, limitRule.channelScope.toGenerated())
            .set(table.CHANNEL_ID, limitRule.channelId())
            .set(table.CHANNEL_GROUP_ID, limitRule.channelGroupId())
            .set(table.OPERATION_TYPE_ID, limitRule.operationTypeId())
            .set(table.USAGE_CONSUMPTION_MODE, limitRule.usageConsumptionMode.toGenerated())
            .set(table.PRIORITY, limitRule.priority.value)
            .set(table.IS_ACTIVE, limitRule.isActive)
            .set(table.VERSION, limitRule.version.value)
            .set(table.STARTS_AT, limitRule.effectiveWindow.startsAt.toOffsetDateTime())
            .set(table.ENDS_AT, limitRule.effectiveWindow.endsAt?.toOffsetDateTime())
            .set(table.METADATA, limitRule.metadata.toJsonb())
        limitRule.updatedAt?.let { update.set(table.UPDATED_AT, it.toOffsetDateTime()) }
        update.where(table.TENANT_ID.eq(tenantId.value))
            .and(table.ID.eq(limitRule.id.value))
            .and(table.VERSION.eq(expectedVersion.value))
            .returning(table.fields().toList())
            .fetchOne()
            ?.toDomain()
    }

    private fun findById(
        tenantId: TenantId,
        id: LimitRuleId,
        forUpdate: Boolean,
    ): LimitRule? = jooqOperation("find limit rule by id") {
        val query = selectRules()
            .where(table.TENANT_ID.eq(tenantId.value))
            .and(table.ID.eq(id.value))
        if (forUpdate) query.forUpdate().fetchOne { it.toDomain() } else query.fetchOne { it.toDomain() }
    }

    private fun selectRules() = context.dsl()
        .select(table.fields().toList())
        .from(table)

    private fun Record.toDomain(): LimitRule {
        val mandateScope = when (requireNotNull(get(table.MANDATE_SCOPE))) {
            GeneratedLimitMandateScope.ALL_OWNER_MANDATES -> MandateScope.AllOwnerMandates
            GeneratedLimitMandateScope.MANDATE -> MandateScope.MandateSpecific(
                MandateId.of(requireNotNull(get(table.MANDATE_ID)))
            )
        }
        val channelScope = when (requireNotNull(get(table.CHANNEL_SCOPE))) {
            GeneratedLimitChannelScope.ALL_CHANNELS -> LimitChannelScope.AllChannels
            GeneratedLimitChannelScope.CHANNEL -> LimitChannelScope.ChannelSpecific(
                ChannelId.of(requireNotNull(get(table.CHANNEL_ID)))
            )
            GeneratedLimitChannelScope.CHANNEL_GROUP -> LimitChannelScope.ChannelGroupSpecific(
                ChannelGroupId.of(requireNotNull(get(table.CHANNEL_GROUP_ID)))
            )
        }
        val operationScope = get(table.OPERATION_TYPE_ID)?.let {
            LimitOperationScope.OperationTypeSpecific(OperationTypeId.of(it))
        } ?: LimitOperationScope.AllOperations
        val limitKind = when (requireNotNull(get(table.LIMIT_KIND))) {
            GeneratedLimitKind.PER_TRANSACTION -> LimitKind.PerTransaction
            GeneratedLimitKind.PERIODIC -> LimitKind.Periodic(
                LimitPeriod.valueOf(requireNotNull(get(table.PERIOD)).name)
            )
        }
        val currency = CurrencyCode.of(requireNotNull(get(table.CURRENCY)))

        return LimitRule(
            id = LimitRuleId.of(requireNotNull(get(table.ID))),
            tenantId = TenantId.of(requireNotNull(get(table.TENANT_ID))),
            ownerPartyProfileId = PartyProfileId.of(requireNotNull(get(table.OWNER_PARTY_PROFILE_ID))),
            name = LimitRuleName.of(requireNotNull(get(table.NAME))),
            description = get(table.DESCRIPTION),
            limitKind = limitKind,
            amount = storedLimitAmount(get(table.AMOUNT), requireNotNull(get(table.AMOUNT_UNLIMITED)), currency),
            mandateScope = mandateScope,
            configurationId = LimitConfigurationId.of(requireNotNull(get(table.CONFIGURATION_ID))),
            channelScope = channelScope,
            operationScope = operationScope,
            usageConsumptionMode = LimitUsageConsumptionMode.valueOf(
                requireNotNull(get(table.USAGE_CONSUMPTION_MODE)).name
            ),
            priority = LimitPriority.of(requireNotNull(get(table.PRIORITY))),
            isActive = requireNotNull(get(table.IS_ACTIVE)),
            version = Version.of(requireNotNull(get(table.VERSION))),
            effectiveWindow = EffectiveWindow(
                startsAt = requireNotNull(get(table.STARTS_AT)).toInstant(),
                endsAt = get(table.ENDS_AT)?.toInstant(),
            ),
            metadata = get(table.METADATA).toMetadata(),
            createdAt = get(table.CREATED_AT)?.toInstant(),
            updatedAt = get(table.UPDATED_AT)?.toInstant(),
        )
    }

    private fun LimitKind.toGenerated(): GeneratedLimitKind = when (this) {
        LimitKind.PerTransaction -> GeneratedLimitKind.PER_TRANSACTION
        is LimitKind.Periodic -> GeneratedLimitKind.PERIODIC
    }

    private fun LimitKind.period(): LimitPeriod? = (this as? LimitKind.Periodic)?.period

    private fun LimitPeriod.toGenerated(): GeneratedLimitPeriod = GeneratedLimitPeriod.valueOf(name)

    private fun MandateScope.toGenerated(): GeneratedLimitMandateScope = when (this) {
        MandateScope.AllOwnerMandates -> GeneratedLimitMandateScope.ALL_OWNER_MANDATES
        is MandateScope.MandateSpecific -> GeneratedLimitMandateScope.MANDATE
    }

    private fun LimitRule.mandateId() =
        (mandateScope as? MandateScope.MandateSpecific)?.mandateId?.value

    private fun LimitChannelScope.toGenerated(): GeneratedLimitChannelScope = when (this) {
        LimitChannelScope.AllChannels -> GeneratedLimitChannelScope.ALL_CHANNELS
        is LimitChannelScope.ChannelSpecific -> GeneratedLimitChannelScope.CHANNEL
        is LimitChannelScope.ChannelGroupSpecific -> GeneratedLimitChannelScope.CHANNEL_GROUP
    }

    private fun LimitRule.channelId() =
        (channelScope as? LimitChannelScope.ChannelSpecific)?.channelId?.value

    private fun LimitRule.channelGroupId() =
        (channelScope as? LimitChannelScope.ChannelGroupSpecific)?.channelGroupId?.value

    private fun LimitRule.operationTypeId() =
        (operationScope as? LimitOperationScope.OperationTypeSpecific)?.operationTypeId?.value

    private fun LimitUsageConsumptionMode.toGenerated(): GeneratedLimitUsageConsumptionMode =
        GeneratedLimitUsageConsumptionMode.valueOf(name)
}

private fun Instant.toOffsetDateTime(): OffsetDateTime = OffsetDateTime.ofInstant(this, ZoneOffset.UTC)
