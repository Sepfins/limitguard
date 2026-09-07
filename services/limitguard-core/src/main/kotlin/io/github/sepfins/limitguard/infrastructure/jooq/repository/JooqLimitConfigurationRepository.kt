package io.github.sepfins.limitguard.infrastructure.jooq.repository

import io.github.sepfins.limitguard.domain.common.ChannelGroupId
import io.github.sepfins.limitguard.domain.common.ChannelId
import io.github.sepfins.limitguard.domain.common.CurrencyCode
import io.github.sepfins.limitguard.domain.common.EffectiveWindow
import io.github.sepfins.limitguard.domain.common.FundingSourceProductId
import io.github.sepfins.limitguard.domain.common.LimitConfigurationId
import io.github.sepfins.limitguard.domain.common.Money
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
import io.github.sepfins.limitguard.infrastructure.jooq.eqPostgresEnum
import io.github.sepfins.limitguard.infrastructure.jooq.jooqField
import io.github.sepfins.limitguard.infrastructure.jooq.jooqOperation
import io.github.sepfins.limitguard.infrastructure.jooq.jooqTable
import io.github.sepfins.limitguard.infrastructure.jooq.nullablePostgresEnum
import io.github.sepfins.limitguard.infrastructure.jooq.postgresEnum
import io.github.sepfins.limitguard.infrastructure.jooq.toJsonb
import io.github.sepfins.limitguard.infrastructure.jooq.toMetadata
import io.github.sepfins.limitguard.port.persistence.LimitConfigurationRepository
import org.jooq.Condition
import org.jooq.Field
import org.jooq.JSONB
import org.jooq.Record
import org.jooq.SelectFieldOrAsterisk
import org.jooq.impl.DSL
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

class JooqLimitConfigurationRepository(
    private val context: JooqPersistenceContext,
) : LimitConfigurationRepository {
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
        LimitConfigurationTable.limitKind.eqPostgresEnum(kind.name(), "limit_kind")
            .and(
                kind.periodName()?.let {
                    LimitConfigurationTable.period.eqPostgresEnum(it, "limit_period")
                } ?: LimitConfigurationTable.period.isNull
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
            LimitOperationScope.AllOperations -> LimitConfigurationTable.operationTypeId.isNull
            is LimitOperationScope.OperationTypeSpecific ->
                LimitConfigurationTable.operationTypeId.eq(scope.operationTypeId.value)
        },
        "find active limit configurations by operation scope",
    )

    override fun findById(tenantId: TenantId, id: LimitConfigurationId): LimitConfiguration? =
        jooqOperation("find limit configuration by id") {
            selectConfigurations()
                .where(LimitConfigurationTable.tenantId.eq(tenantId.value))
                .and(LimitConfigurationTable.id.eq(id.value))
                .fetchOne { it.toDomain() }
        }

    override fun findByCode(
        tenantId: TenantId,
        code: LimitConfigurationCode,
    ): LimitConfiguration? = jooqOperation("find limit configuration by code") {
        selectConfigurations()
            .where(LimitConfigurationTable.tenantId.eq(tenantId.value))
            .and(LimitConfigurationTable.code.eq(code.value))
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
            .insertInto(LimitConfigurationTable.table)
            .set(LimitConfigurationTable.id, limitConfiguration.id.value)
            .set(LimitConfigurationTable.tenantId, tenantId.value)
            .set(LimitConfigurationTable.parentConfigurationId, limitConfiguration.parentConfigurationId?.value)
            .set(LimitConfigurationTable.code, limitConfiguration.code.value)
            .set(LimitConfigurationTable.name, limitConfiguration.name.value)
            .set(LimitConfigurationTable.description, limitConfiguration.description)
            .set(
                LimitConfigurationTable.limitKind,
                postgresEnum(limitConfiguration.limitKind.name(), "limit_kind"),
            )
            .set(
                LimitConfigurationTable.period,
                nullablePostgresEnum(limitConfiguration.limitKind.periodName(), "limit_period"),
            )
            .set(LimitConfigurationTable.currency, limitConfiguration.maxAssignableAmount.currency.value)
            .set(
                LimitConfigurationTable.fundingScope,
                postgresEnum(limitConfiguration.fundingScope.name(), "limit_configuration_funding_scope"),
            )
            .set(
                LimitConfigurationTable.fundingSourceCategory,
                nullablePostgresEnum(
                    limitConfiguration.fundingScope.categoryName(),
                    "funding_source_category",
                ),
            )
            .set(LimitConfigurationTable.fundingSourceProductId, limitConfiguration.fundingScope.productId())
            .set(
                LimitConfigurationTable.channelScope,
                postgresEnum(limitConfiguration.channelScope.name(), "limit_channel_scope"),
            )
            .set(LimitConfigurationTable.channelId, limitConfiguration.channelScope.channelId())
            .set(LimitConfigurationTable.channelGroupId, limitConfiguration.channelScope.channelGroupId())
            .set(LimitConfigurationTable.operationTypeId, limitConfiguration.operationScope.operationTypeId())
            .set(LimitConfigurationTable.maxAssignableAmount, limitConfiguration.maxAssignableAmount.amount)
            .set(LimitConfigurationTable.defaultAmount, limitConfiguration.defaultAmount?.amount)
            .set(LimitConfigurationTable.priority, limitConfiguration.priority.value)
            .set(LimitConfigurationTable.isActive, limitConfiguration.isActive)
            .set(LimitConfigurationTable.startsAt, limitConfiguration.effectiveWindow.startsAt)
            .set(LimitConfigurationTable.endsAt, limitConfiguration.effectiveWindow.endsAt)
            .set(LimitConfigurationTable.metadata, limitConfiguration.metadata.toJsonb())
        limitConfiguration.createdAt?.let { insert.set(LimitConfigurationTable.createdAt, it) }
        limitConfiguration.updatedAt?.let { insert.set(LimitConfigurationTable.updatedAt, it) }
        requireNotNull(insert.returning(LimitConfigurationTable.fields).fetchOne()).toDomain()
    }

    override fun update(
        tenantId: TenantId,
        limitConfiguration: LimitConfiguration,
    ): LimitConfiguration = jooqOperation("update limit configuration") {
        require(limitConfiguration.tenantId == tenantId) {
            "limit configuration tenant does not match repository tenant"
        }
        val update = context.dsl()
            .update(LimitConfigurationTable.table)
            .set(LimitConfigurationTable.parentConfigurationId, limitConfiguration.parentConfigurationId?.value)
            .set(LimitConfigurationTable.code, limitConfiguration.code.value)
            .set(LimitConfigurationTable.name, limitConfiguration.name.value)
            .set(LimitConfigurationTable.description, limitConfiguration.description)
            .set(
                LimitConfigurationTable.limitKind,
                postgresEnum(limitConfiguration.limitKind.name(), "limit_kind"),
            )
            .set(
                LimitConfigurationTable.period,
                nullablePostgresEnum(limitConfiguration.limitKind.periodName(), "limit_period"),
            )
            .set(LimitConfigurationTable.currency, limitConfiguration.maxAssignableAmount.currency.value)
            .set(
                LimitConfigurationTable.fundingScope,
                postgresEnum(limitConfiguration.fundingScope.name(), "limit_configuration_funding_scope"),
            )
            .set(
                LimitConfigurationTable.fundingSourceCategory,
                nullablePostgresEnum(
                    limitConfiguration.fundingScope.categoryName(),
                    "funding_source_category",
                ),
            )
            .set(LimitConfigurationTable.fundingSourceProductId, limitConfiguration.fundingScope.productId())
            .set(
                LimitConfigurationTable.channelScope,
                postgresEnum(limitConfiguration.channelScope.name(), "limit_channel_scope"),
            )
            .set(LimitConfigurationTable.channelId, limitConfiguration.channelScope.channelId())
            .set(LimitConfigurationTable.channelGroupId, limitConfiguration.channelScope.channelGroupId())
            .set(LimitConfigurationTable.operationTypeId, limitConfiguration.operationScope.operationTypeId())
            .set(LimitConfigurationTable.maxAssignableAmount, limitConfiguration.maxAssignableAmount.amount)
            .set(LimitConfigurationTable.defaultAmount, limitConfiguration.defaultAmount?.amount)
            .set(LimitConfigurationTable.priority, limitConfiguration.priority.value)
            .set(LimitConfigurationTable.isActive, limitConfiguration.isActive)
            .set(LimitConfigurationTable.startsAt, limitConfiguration.effectiveWindow.startsAt)
            .set(LimitConfigurationTable.endsAt, limitConfiguration.effectiveWindow.endsAt)
            .set(LimitConfigurationTable.metadata, limitConfiguration.metadata.toJsonb())
        limitConfiguration.updatedAt?.let { update.set(LimitConfigurationTable.updatedAt, it) }
        requireNotNull(
            update.where(LimitConfigurationTable.tenantId.eq(tenantId.value))
                .and(LimitConfigurationTable.id.eq(limitConfiguration.id.value))
                .returning(LimitConfigurationTable.fields)
                .fetchOne()
        ) { "limit configuration not found: ${limitConfiguration.id.value}" }.toDomain()
    }

    private fun findActive(
        tenantId: TenantId,
        at: Instant,
        condition: Condition,
        operation: String,
    ): List<LimitConfiguration> = jooqOperation(operation) {
        selectConfigurations()
            .where(LimitConfigurationTable.tenantId.eq(tenantId.value))
            .and(LimitConfigurationTable.isActive.isTrue)
            .and(LimitConfigurationTable.startsAt.le(at))
            .and(LimitConfigurationTable.endsAt.isNull.or(LimitConfigurationTable.endsAt.gt(at)))
            .and(condition)
            .orderBy(LimitConfigurationTable.priority, LimitConfigurationTable.code, LimitConfigurationTable.id)
            .fetch { it.toDomain() }
    }

    private fun channelScopeCondition(scope: LimitChannelScope): Condition =
        LimitConfigurationTable.channelScope.eqPostgresEnum(scope.name(), "limit_channel_scope")
            .and(
                when (scope) {
                    LimitChannelScope.AllChannels -> DSL.trueCondition()
                    is LimitChannelScope.ChannelSpecific -> LimitConfigurationTable.channelId.eq(scope.channelId.value)
                    is LimitChannelScope.ChannelGroupSpecific ->
                        LimitConfigurationTable.channelGroupId.eq(scope.channelGroupId.value)
                }
            )

    private fun fundingScopeCondition(scope: LimitConfigurationFundingScope): Condition =
        LimitConfigurationTable.fundingScope.eqPostgresEnum(
            scope.name(),
            "limit_configuration_funding_scope",
        ).and(
            when (scope) {
                LimitConfigurationFundingScope.AllFundingSources -> DSL.trueCondition()
                is LimitConfigurationFundingScope.FundingSourceCategorySpecific ->
                    LimitConfigurationTable.fundingSourceCategory.eqPostgresEnum(
                        scope.sourceCategory.name,
                        "funding_source_category",
                    )
                is LimitConfigurationFundingScope.FundingSourceProductSpecific ->
                    LimitConfigurationTable.fundingSourceProductId.eq(scope.productId.value)
            }
        )

    private fun selectConfigurations() = context.dsl()
        .select(LimitConfigurationTable.fields)
        .from(LimitConfigurationTable.table)

    private fun Record.toDomain(): LimitConfiguration {
        val currency = CurrencyCode.of(requireNotNull(get(LimitConfigurationTable.currency)))
        val limitKind = when (val kind = requireNotNull(get(LimitConfigurationTable.limitKind))) {
            "PER_TRANSACTION" -> LimitKind.PerTransaction
            "PERIODIC" -> LimitKind.Periodic(
                LimitPeriod.valueOf(requireNotNull(get(LimitConfigurationTable.period)))
            )
            else -> error("unsupported limit configuration kind: $kind")
        }
        val fundingScope = when (val scope = requireNotNull(get(LimitConfigurationTable.fundingScope))) {
            "ALL_FUNDING_SOURCES" -> LimitConfigurationFundingScope.AllFundingSources
            "FUNDING_SOURCE_CATEGORY" -> LimitConfigurationFundingScope.FundingSourceCategorySpecific(
                FundingSourceCategory.valueOf(
                    requireNotNull(get(LimitConfigurationTable.fundingSourceCategory))
                )
            )
            "FUNDING_SOURCE_PRODUCT" -> LimitConfigurationFundingScope.FundingSourceProductSpecific(
                FundingSourceProductId.of(
                    requireNotNull(get(LimitConfigurationTable.fundingSourceProductId))
                )
            )
            else -> error("unsupported limit configuration funding scope: $scope")
        }
        val channelScope = when (val scope = requireNotNull(get(LimitConfigurationTable.channelScope))) {
            "ALL_CHANNELS" -> LimitChannelScope.AllChannels
            "CHANNEL" -> LimitChannelScope.ChannelSpecific(
                ChannelId.of(requireNotNull(get(LimitConfigurationTable.channelId)))
            )
            "CHANNEL_GROUP" -> LimitChannelScope.ChannelGroupSpecific(
                ChannelGroupId.of(requireNotNull(get(LimitConfigurationTable.channelGroupId)))
            )
            else -> error("unsupported limit configuration channel scope: $scope")
        }
        val operationScope = get(LimitConfigurationTable.operationTypeId)?.let {
            LimitOperationScope.OperationTypeSpecific(OperationTypeId.of(it))
        } ?: LimitOperationScope.AllOperations

        return LimitConfiguration(
            id = LimitConfigurationId.of(requireNotNull(get(LimitConfigurationTable.id))),
            tenantId = TenantId.of(requireNotNull(get(LimitConfigurationTable.tenantId))),
            parentConfigurationId = get(LimitConfigurationTable.parentConfigurationId)
                ?.let(LimitConfigurationId::of),
            code = LimitConfigurationCode.of(requireNotNull(get(LimitConfigurationTable.code))),
            name = LimitConfigurationName.of(requireNotNull(get(LimitConfigurationTable.name))),
            description = get(LimitConfigurationTable.description),
            limitKind = limitKind,
            fundingScope = fundingScope,
            channelScope = channelScope,
            operationScope = operationScope,
            maxAssignableAmount = Money.positive(
                requireNotNull(get(LimitConfigurationTable.maxAssignableAmount)),
                currency,
            ),
            defaultAmount = get(LimitConfigurationTable.defaultAmount)?.let { Money.positive(it, currency) },
            priority = LimitPriority.of(requireNotNull(get(LimitConfigurationTable.priority))),
            isActive = requireNotNull(get(LimitConfigurationTable.isActive)),
            effectiveWindow = EffectiveWindow(
                startsAt = requireNotNull(get(LimitConfigurationTable.startsAt)),
                endsAt = get(LimitConfigurationTable.endsAt),
            ),
            metadata = get(LimitConfigurationTable.metadata).toMetadata(),
            createdAt = get(LimitConfigurationTable.createdAt),
            updatedAt = get(LimitConfigurationTable.updatedAt),
        )
    }

    private fun LimitKind.name(): String = when (this) {
        LimitKind.PerTransaction -> "PER_TRANSACTION"
        is LimitKind.Periodic -> "PERIODIC"
    }

    private fun LimitKind.periodName(): String? = (this as? LimitKind.Periodic)?.period?.name

    private fun LimitConfigurationFundingScope.name(): String = when (this) {
        LimitConfigurationFundingScope.AllFundingSources -> "ALL_FUNDING_SOURCES"
        is LimitConfigurationFundingScope.FundingSourceCategorySpecific -> "FUNDING_SOURCE_CATEGORY"
        is LimitConfigurationFundingScope.FundingSourceProductSpecific -> "FUNDING_SOURCE_PRODUCT"
    }

    private fun LimitConfigurationFundingScope.categoryName(): String? =
        (this as? LimitConfigurationFundingScope.FundingSourceCategorySpecific)?.sourceCategory?.name

    private fun LimitConfigurationFundingScope.productId(): UUID? =
        (this as? LimitConfigurationFundingScope.FundingSourceProductSpecific)?.productId?.value

    private fun LimitChannelScope.name(): String = when (this) {
        LimitChannelScope.AllChannels -> "ALL_CHANNELS"
        is LimitChannelScope.ChannelSpecific -> "CHANNEL"
        is LimitChannelScope.ChannelGroupSpecific -> "CHANNEL_GROUP"
    }

    private fun LimitChannelScope.channelId(): UUID? =
        (this as? LimitChannelScope.ChannelSpecific)?.channelId?.value

    private fun LimitChannelScope.channelGroupId(): UUID? =
        (this as? LimitChannelScope.ChannelGroupSpecific)?.channelGroupId?.value

    private fun LimitOperationScope.operationTypeId(): UUID? =
        (this as? LimitOperationScope.OperationTypeSpecific)?.operationTypeId?.value
}

private object LimitConfigurationTable {
    const val NAME = "limit_configuration"
    val table = jooqTable(NAME)
    val id: Field<UUID> = jooqField(NAME, "id", UUID::class.java)
    val tenantId: Field<UUID> = jooqField(NAME, "tenant_id", UUID::class.java)
    val parentConfigurationId: Field<UUID> = jooqField(NAME, "parent_configuration_id", UUID::class.java)
    val code: Field<String> = jooqField(NAME, "code", String::class.java)
    val name: Field<String> = jooqField(NAME, "name", String::class.java)
    val description: Field<String> = jooqField(NAME, "description", String::class.java)
    val limitKind: Field<String> = jooqField(NAME, "limit_kind", String::class.java)
    val period: Field<String> = jooqField(NAME, "period", String::class.java)
    val currency: Field<String> = jooqField(NAME, "currency", String::class.java)
    val fundingScope: Field<String> = jooqField(NAME, "funding_scope", String::class.java)
    val fundingSourceCategory: Field<String> = jooqField(NAME, "funding_source_category", String::class.java)
    val fundingSourceProductId: Field<UUID> = jooqField(NAME, "funding_source_product_id", UUID::class.java)
    val channelScope: Field<String> = jooqField(NAME, "channel_scope", String::class.java)
    val channelId: Field<UUID> = jooqField(NAME, "channel_id", UUID::class.java)
    val channelGroupId: Field<UUID> = jooqField(NAME, "channel_group_id", UUID::class.java)
    val operationTypeId: Field<UUID> = jooqField(NAME, "operation_type_id", UUID::class.java)
    val maxAssignableAmount: Field<BigDecimal> =
        jooqField(NAME, "max_assignable_amount", BigDecimal::class.java)
    val defaultAmount: Field<BigDecimal> = jooqField(NAME, "default_amount", BigDecimal::class.java)
    val priority: Field<Int> = jooqField(NAME, "priority", Int::class.java)
    val isActive: Field<Boolean> = jooqField(NAME, "is_active", Boolean::class.java)
    val startsAt: Field<Instant> = jooqField(NAME, "starts_at", Instant::class.java)
    val endsAt: Field<Instant> = jooqField(NAME, "ends_at", Instant::class.java)
    val metadata: Field<JSONB> = jooqField(NAME, "metadata", JSONB::class.java)
    val createdAt: Field<Instant> = jooqField(NAME, "created_at", Instant::class.java)
    val updatedAt: Field<Instant> = jooqField(NAME, "updated_at", Instant::class.java)
    val fields: List<SelectFieldOrAsterisk> = listOf(
        id,
        tenantId,
        parentConfigurationId,
        code,
        name,
        description,
        limitKind,
        period,
        currency,
        fundingScope,
        fundingSourceCategory,
        fundingSourceProductId,
        channelScope,
        channelId,
        channelGroupId,
        operationTypeId,
        maxAssignableAmount,
        defaultAmount,
        priority,
        isActive,
        startsAt,
        endsAt,
        metadata,
        createdAt,
        updatedAt,
    )
}
