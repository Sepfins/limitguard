package io.github.sepfins.limitguard.infrastructure.jooq.repository

import io.github.sepfins.limitguard.api.errors.InfrastructureException
import io.github.sepfins.limitguard.domain.common.ChannelGroupId
import io.github.sepfins.limitguard.domain.common.ChannelId
import io.github.sepfins.limitguard.domain.common.CurrencyCode
import io.github.sepfins.limitguard.domain.common.EffectiveWindow
import io.github.sepfins.limitguard.domain.common.FundingSourceProductId
import io.github.sepfins.limitguard.domain.common.LimitConfigurationId
import io.github.sepfins.limitguard.domain.common.Money
import io.github.sepfins.limitguard.domain.limit.LimitAmount
import io.github.sepfins.limitguard.domain.limit.LimitUsageConsumptionMode
import io.github.sepfins.limitguard.domain.common.OperationTypeId
import io.github.sepfins.limitguard.domain.common.TenantId
import io.github.sepfins.limitguard.domain.funding.FundingSourceCategory
import io.github.sepfins.limitguard.domain.limit.LimitChannelScope
import io.github.sepfins.limitguard.domain.limit.LimitConfiguration
import io.github.sepfins.limitguard.domain.limit.LimitConfigurationCode
import io.github.sepfins.limitguard.domain.limit.LimitConfigurationFundingScope
import io.github.sepfins.limitguard.domain.limit.LimitConfigurationName
import io.github.sepfins.limitguard.domain.limit.LimitKind
import io.github.sepfins.limitguard.domain.limit.LimitOperationScope
import io.github.sepfins.limitguard.domain.limit.LimitPeriod
import io.github.sepfins.limitguard.domain.limit.LimitPriority
import io.github.sepfins.limitguard.infrastructure.jooq.JooqPersistenceContext
import io.github.sepfins.limitguard.infrastructure.jooq.jooqOperation
import io.github.sepfins.limitguard.infrastructure.jooq.storedAmount
import io.github.sepfins.limitguard.infrastructure.jooq.storedLimitAmount
import io.github.sepfins.limitguard.infrastructure.jooq.toJsonb
import io.github.sepfins.limitguard.infrastructure.jooq.toMetadata
import io.github.sepfins.limitguard.infrastructure.jooq.generated.enums.FundingSourceCategory as GeneratedFundingSourceCategory
import io.github.sepfins.limitguard.infrastructure.jooq.generated.enums.LimitChannelScope as GeneratedLimitChannelScope
import io.github.sepfins.limitguard.infrastructure.jooq.generated.enums.LimitConfigurationFundingScope as GeneratedLimitConfigurationFundingScope
import io.github.sepfins.limitguard.infrastructure.jooq.generated.enums.LimitKind as GeneratedLimitKind
import io.github.sepfins.limitguard.infrastructure.jooq.generated.enums.LimitPeriod as GeneratedLimitPeriod
import io.github.sepfins.limitguard.infrastructure.jooq.generated.enums.LimitUsageConsumptionMode as GeneratedLimitUsageConsumptionMode
import io.github.sepfins.limitguard.infrastructure.jooq.generated.tables.LimitConfiguration as GeneratedLimitConfigurationTable
import io.github.sepfins.limitguard.port.persistence.LimitConfigurationRepository
import org.jooq.Condition
import org.jooq.Record
import org.jooq.impl.DSL
import java.sql.Connection
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

class JooqLimitConfigurationRepository(
    private val context: JooqPersistenceContext,
) : LimitConfigurationRepository {
    private val table = GeneratedLimitConfigurationTable.LIMIT_CONFIGURATION

    override fun lockFundingEnvelopes(tenantId: TenantId) = jooqOperation("lock funding configuration envelopes") {
        context.requireTransaction("locking funding configuration envelopes")
        val isolation = context.dsl().connectionResult { it.transactionIsolation }
        if (isolation != Connection.TRANSACTION_READ_COMMITTED) {
            throw InfrastructureException("funding configuration envelopes require READ COMMITTED transaction isolation")
        }
        val lockKey = "LimitGuard funding envelopes:${tenantId.value}"
        context.dsl().fetch("select pg_advisory_xact_lock(hashtextextended(?, 0))", lockKey)
        Unit
    }

    override fun findAll(tenantId: TenantId): List<LimitConfiguration> =
        jooqOperation("find all limit configurations") {
            selectConfigurations()
                .where(table.TENANT_ID.eq(tenantId.value))
                .orderBy(table.CODE, table.ID)
                .fetch { it.toDomain() }
        }

    override fun findAllActive(tenantId: TenantId, at: Instant): List<LimitConfiguration> =
        findActive(tenantId, at, DSL.trueCondition(), "find active limit configurations")

    override fun findActiveByChannelScope(
        tenantId: TenantId,
        scope: LimitChannelScope,
        at: Instant,
    ): List<LimitConfiguration> = findActive(
        tenantId,
        at,
        channelScopeCondition(scope),
        "find active limit configurations by channel scope",
    )

    override fun findActiveByLimitKind(
        tenantId: TenantId,
        kind: LimitKind,
        at: Instant,
    ): List<LimitConfiguration> = findActive(
        tenantId,
        at,
        table.LIMIT_KIND.eq(kind.toGenerated())
            .and(
                kind.period()?.let { table.PERIOD.eq(it.toGenerated()) } ?: table.PERIOD.isNull
            ),
        "find active limit configurations by kind",
    )

    override fun findActiveByFundingScope(
        tenantId: TenantId,
        scope: LimitConfigurationFundingScope,
        at: Instant,
    ): List<LimitConfiguration> = findActive(
        tenantId,
        at,
        fundingScopeCondition(scope),
        "find active limit configurations by funding scope",
    )

    override fun findActiveByOperationScope(
        tenantId: TenantId,
        scope: LimitOperationScope,
        at: Instant,
    ): List<LimitConfiguration> = findActive(
        tenantId,
        at,
        when (scope) {
            LimitOperationScope.AllOperations -> table.OPERATION_TYPE_ID.isNull
            is LimitOperationScope.OperationTypeSpecific -> table.OPERATION_TYPE_ID.eq(scope.operationTypeId.value)
        },
        "find active limit configurations by operation scope",
    )

    override fun findById(tenantId: TenantId, id: LimitConfigurationId): LimitConfiguration? =
        jooqOperation("find limit configuration by id") {
            selectConfigurations()
                .where(table.TENANT_ID.eq(tenantId.value))
                .and(table.ID.eq(id.value))
                .fetchOne { it.toDomain() }
        }

    override fun findByIdForUpdate(
        tenantId: TenantId,
        id: LimitConfigurationId,
    ): LimitConfiguration? = jooqOperation("lock limit configuration by id") {
        context.requireTransaction("locking a limit configuration row")
        selectConfigurations()
            .where(table.TENANT_ID.eq(tenantId.value))
            .and(table.ID.eq(id.value))
            .forUpdate()
            .fetchOne { it.toDomain() }
    }

    override fun findByCode(
        tenantId: TenantId,
        code: LimitConfigurationCode,
    ): LimitConfiguration? = jooqOperation("find limit configuration by code") {
        selectConfigurations()
            .where(table.TENANT_ID.eq(tenantId.value))
            .and(table.CODE.eq(code.value))
            .fetchOne { it.toDomain() }
    }

    override fun insert(
        tenantId: TenantId,
        limitConfiguration: LimitConfiguration,
    ): LimitConfiguration = jooqOperation("insert limit configuration") {
        require(limitConfiguration.tenantId == tenantId) {
            "limit configuration tenant does not match repository tenant"
        }
        val insert = context.dsl()
            .insertInto(table)
            .set(table.ID, limitConfiguration.id.value)
            .set(table.TENANT_ID, tenantId.value)
            .set(table.PARENT_CONFIGURATION_ID, limitConfiguration.parentConfigurationId?.value)
            .set(table.CODE, limitConfiguration.code.value)
            .set(table.NAME, limitConfiguration.name.value)
            .set(table.DESCRIPTION, limitConfiguration.description)
            .set(table.LIMIT_KIND, limitConfiguration.limitKind.toGenerated())
            .set(table.PERIOD, limitConfiguration.limitKind.period()?.toGenerated())
            .set(table.CURRENCY, limitConfiguration.currency.value)
            .set(table.FUNDING_SCOPE, limitConfiguration.fundingScope.toGenerated())
            .set(table.FUNDING_SOURCE_CATEGORY, limitConfiguration.fundingScope.category()?.toGenerated())
            .set(table.FUNDING_SOURCE_PRODUCT_ID, limitConfiguration.fundingScope.productId())
            .set(table.CHANNEL_SCOPE, limitConfiguration.channelScope.toGenerated())
            .set(table.CHANNEL_ID, limitConfiguration.channelScope.channelId())
            .set(table.CHANNEL_GROUP_ID, limitConfiguration.channelScope.channelGroupId())
            .set(table.OPERATION_TYPE_ID, limitConfiguration.operationScope.operationTypeId())
            .set(table.MAX_ASSIGNABLE_AMOUNT, limitConfiguration.maxAssignableAmount.storedAmount)
            .set(table.MAX_ASSIGNABLE_UNLIMITED, limitConfiguration.maxAssignableAmount is LimitAmount.Unlimited)
            .set(table.DEFAULT_AMOUNT, limitConfiguration.defaultAmount?.amount)
            .set(table.PRIORITY, limitConfiguration.priority.value)
            .set(table.USAGE_CONSUMPTION_MODE, GeneratedLimitUsageConsumptionMode.valueOf(limitConfiguration.usageConsumptionMode.name))
            .set(table.IS_ACTIVE, limitConfiguration.isActive)
            .set(table.STARTS_AT, limitConfiguration.effectiveWindow.startsAt.toOffsetDateTime())
            .set(table.ENDS_AT, limitConfiguration.effectiveWindow.endsAt?.toOffsetDateTime())
            .set(table.METADATA, limitConfiguration.metadata.toJsonb())
        limitConfiguration.createdAt?.let { insert.set(table.CREATED_AT, it.toOffsetDateTime()) }
        limitConfiguration.updatedAt?.let { insert.set(table.UPDATED_AT, it.toOffsetDateTime()) }
        requireNotNull(insert.returning(table.fields().toList()).fetchOne()).toDomain()
    }

    override fun update(
        tenantId: TenantId,
        limitConfiguration: LimitConfiguration,
    ): LimitConfiguration = jooqOperation("update limit configuration") {
        require(limitConfiguration.tenantId == tenantId) {
            "limit configuration tenant does not match repository tenant"
        }
        val update = context.dsl()
            .update(table)
            .set(table.PARENT_CONFIGURATION_ID, limitConfiguration.parentConfigurationId?.value)
            .set(table.CODE, limitConfiguration.code.value)
            .set(table.NAME, limitConfiguration.name.value)
            .set(table.DESCRIPTION, limitConfiguration.description)
            .set(table.LIMIT_KIND, limitConfiguration.limitKind.toGenerated())
            .set(table.PERIOD, limitConfiguration.limitKind.period()?.toGenerated())
            .set(table.CURRENCY, limitConfiguration.currency.value)
            .set(table.FUNDING_SCOPE, limitConfiguration.fundingScope.toGenerated())
            .set(table.FUNDING_SOURCE_CATEGORY, limitConfiguration.fundingScope.category()?.toGenerated())
            .set(table.FUNDING_SOURCE_PRODUCT_ID, limitConfiguration.fundingScope.productId())
            .set(table.CHANNEL_SCOPE, limitConfiguration.channelScope.toGenerated())
            .set(table.CHANNEL_ID, limitConfiguration.channelScope.channelId())
            .set(table.CHANNEL_GROUP_ID, limitConfiguration.channelScope.channelGroupId())
            .set(table.OPERATION_TYPE_ID, limitConfiguration.operationScope.operationTypeId())
            .set(table.MAX_ASSIGNABLE_AMOUNT, limitConfiguration.maxAssignableAmount.storedAmount)
            .set(table.MAX_ASSIGNABLE_UNLIMITED, limitConfiguration.maxAssignableAmount is LimitAmount.Unlimited)
            .set(table.DEFAULT_AMOUNT, limitConfiguration.defaultAmount?.amount)
            .set(table.PRIORITY, limitConfiguration.priority.value)
            .set(table.USAGE_CONSUMPTION_MODE, GeneratedLimitUsageConsumptionMode.valueOf(limitConfiguration.usageConsumptionMode.name))
            .set(table.IS_ACTIVE, limitConfiguration.isActive)
            .set(table.STARTS_AT, limitConfiguration.effectiveWindow.startsAt.toOffsetDateTime())
            .set(table.ENDS_AT, limitConfiguration.effectiveWindow.endsAt?.toOffsetDateTime())
            .set(table.METADATA, limitConfiguration.metadata.toJsonb())
        limitConfiguration.updatedAt?.let { update.set(table.UPDATED_AT, it.toOffsetDateTime()) }
        requireNotNull(
            update.where(table.TENANT_ID.eq(tenantId.value))
                .and(table.ID.eq(limitConfiguration.id.value))
                .returning(table.fields().toList())
                .fetchOne()
        ) { "limit configuration not found: ${limitConfiguration.id.value}" }.toDomain()
    }

    private fun findActive(
        tenantId: TenantId,
        at: Instant,
        condition: Condition,
        operation: String,
    ): List<LimitConfiguration> = jooqOperation(operation) {
        val timestamp = at.toOffsetDateTime()
        selectConfigurations()
            .where(table.TENANT_ID.eq(tenantId.value))
            .and(table.IS_ACTIVE.isTrue)
            .and(table.STARTS_AT.le(timestamp))
            .and(table.ENDS_AT.isNull.or(table.ENDS_AT.gt(timestamp)))
            .and(condition)
            .orderBy(table.PRIORITY, table.CODE, table.ID)
            .fetch { it.toDomain() }
    }

    private fun channelScopeCondition(scope: LimitChannelScope): Condition =
        table.CHANNEL_SCOPE.eq(scope.toGenerated())
            .and(
                when (scope) {
                    LimitChannelScope.AllChannels -> DSL.trueCondition()
                    is LimitChannelScope.ChannelSpecific -> table.CHANNEL_ID.eq(scope.channelId.value)
                    is LimitChannelScope.ChannelGroupSpecific -> table.CHANNEL_GROUP_ID.eq(scope.channelGroupId.value)
                }
            )

    private fun fundingScopeCondition(scope: LimitConfigurationFundingScope): Condition =
        table.FUNDING_SCOPE.eq(scope.toGenerated()).and(
            when (scope) {
                LimitConfigurationFundingScope.AllFundingSources -> DSL.trueCondition()
                is LimitConfigurationFundingScope.FundingSourceCategorySpecific ->
                    table.FUNDING_SOURCE_CATEGORY.eq(scope.sourceCategory.toGenerated())
                is LimitConfigurationFundingScope.FundingSourceProductSpecific ->
                    table.FUNDING_SOURCE_PRODUCT_ID.eq(scope.productId.value)
            }
        )

    private fun selectConfigurations() = context.dsl()
        .select(table.fields().toList())
        .from(table)

    private fun Record.toDomain(): LimitConfiguration {
        val currency = CurrencyCode.of(requireNotNull(get(table.CURRENCY)))
        val limitKind = when (requireNotNull(get(table.LIMIT_KIND))) {
            GeneratedLimitKind.PER_TRANSACTION -> LimitKind.PerTransaction
            GeneratedLimitKind.PERIODIC -> LimitKind.Periodic(
                LimitPeriod.valueOf(requireNotNull(get(table.PERIOD)).name)
            )
        }
        val fundingScope = when (requireNotNull(get(table.FUNDING_SCOPE))) {
            GeneratedLimitConfigurationFundingScope.ALL_FUNDING_SOURCES ->
                LimitConfigurationFundingScope.AllFundingSources
            GeneratedLimitConfigurationFundingScope.FUNDING_SOURCE_CATEGORY ->
                LimitConfigurationFundingScope.FundingSourceCategorySpecific(
                    FundingSourceCategory.valueOf(requireNotNull(get(table.FUNDING_SOURCE_CATEGORY)).name)
                )
            GeneratedLimitConfigurationFundingScope.FUNDING_SOURCE_PRODUCT ->
                LimitConfigurationFundingScope.FundingSourceProductSpecific(
                    FundingSourceProductId.of(requireNotNull(get(table.FUNDING_SOURCE_PRODUCT_ID)))
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

        return LimitConfiguration(
            id = LimitConfigurationId.of(requireNotNull(get(table.ID))),
            tenantId = TenantId.of(requireNotNull(get(table.TENANT_ID))),
            parentConfigurationId = get(table.PARENT_CONFIGURATION_ID)?.let(LimitConfigurationId::of),
            code = LimitConfigurationCode.of(requireNotNull(get(table.CODE))),
            name = LimitConfigurationName.of(requireNotNull(get(table.NAME))),
            description = get(table.DESCRIPTION),
            limitKind = limitKind,
            fundingScope = fundingScope,
            channelScope = channelScope,
            operationScope = operationScope,
            maxAssignableAmount = storedLimitAmount(
                get(table.MAX_ASSIGNABLE_AMOUNT), requireNotNull(get(table.MAX_ASSIGNABLE_UNLIMITED)), currency,
            ),
            defaultAmount = get(table.DEFAULT_AMOUNT)?.let { Money.positive(it, currency) },
            priority = LimitPriority.of(requireNotNull(get(table.PRIORITY))),
            usageConsumptionMode = LimitUsageConsumptionMode.valueOf(requireNotNull(get(table.USAGE_CONSUMPTION_MODE)).name),
            isActive = requireNotNull(get(table.IS_ACTIVE)),
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

    private fun LimitConfigurationFundingScope.toGenerated(): GeneratedLimitConfigurationFundingScope = when (this) {
        LimitConfigurationFundingScope.AllFundingSources ->
            GeneratedLimitConfigurationFundingScope.ALL_FUNDING_SOURCES
        is LimitConfigurationFundingScope.FundingSourceCategorySpecific ->
            GeneratedLimitConfigurationFundingScope.FUNDING_SOURCE_CATEGORY
        is LimitConfigurationFundingScope.FundingSourceProductSpecific ->
            GeneratedLimitConfigurationFundingScope.FUNDING_SOURCE_PRODUCT
    }

    private fun LimitConfigurationFundingScope.category(): FundingSourceCategory? =
        (this as? LimitConfigurationFundingScope.FundingSourceCategorySpecific)?.sourceCategory

    private fun FundingSourceCategory.toGenerated(): GeneratedFundingSourceCategory =
        GeneratedFundingSourceCategory.valueOf(name)

    private fun LimitConfigurationFundingScope.productId() =
        (this as? LimitConfigurationFundingScope.FundingSourceProductSpecific)?.productId?.value

    private fun LimitChannelScope.toGenerated(): GeneratedLimitChannelScope = when (this) {
        LimitChannelScope.AllChannels -> GeneratedLimitChannelScope.ALL_CHANNELS
        is LimitChannelScope.ChannelSpecific -> GeneratedLimitChannelScope.CHANNEL
        is LimitChannelScope.ChannelGroupSpecific -> GeneratedLimitChannelScope.CHANNEL_GROUP
    }

    private fun LimitChannelScope.channelId(): java.util.UUID? =
        (this as? LimitChannelScope.ChannelSpecific)?.channelId?.value

    private fun LimitChannelScope.channelGroupId(): java.util.UUID? =
        (this as? LimitChannelScope.ChannelGroupSpecific)?.channelGroupId?.value

    private fun LimitOperationScope.operationTypeId(): java.util.UUID? =
        (this as? LimitOperationScope.OperationTypeSpecific)?.operationTypeId?.value
}

private fun Instant.toOffsetDateTime(): OffsetDateTime = OffsetDateTime.ofInstant(this, ZoneOffset.UTC)
