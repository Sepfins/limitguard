package io.github.sepfins.limitguard.infrastructure.jooq.repository

import io.github.sepfins.limitguard.domain.common.CurrencyCode
import io.github.sepfins.limitguard.domain.common.LimitConfigurationId
import io.github.sepfins.limitguard.domain.common.LimitRuleId
import io.github.sepfins.limitguard.domain.common.MandateId
import io.github.sepfins.limitguard.domain.common.PartyProfileId
import io.github.sepfins.limitguard.domain.common.TenantId
import io.github.sepfins.limitguard.domain.common.Version
import io.github.sepfins.limitguard.domain.limit.LimitAmount
import io.github.sepfins.limitguard.domain.limit.LimitRule
import io.github.sepfins.limitguard.domain.limit.LimitRuleTarget
import io.github.sepfins.limitguard.domain.mandate.MandateScope
import io.github.sepfins.limitguard.infrastructure.jooq.JooqPersistenceContext
import io.github.sepfins.limitguard.infrastructure.jooq.jooqOperation
import io.github.sepfins.limitguard.infrastructure.jooq.storedAmount
import io.github.sepfins.limitguard.infrastructure.jooq.storedLimitAmount
import io.github.sepfins.limitguard.infrastructure.jooq.toJsonb
import io.github.sepfins.limitguard.infrastructure.jooq.toMetadata
import io.github.sepfins.limitguard.infrastructure.jooq.generated.tables.LimitConfiguration as GeneratedLimitConfigurationTable
import io.github.sepfins.limitguard.infrastructure.jooq.generated.tables.LimitRule as GeneratedLimitRuleTable
import io.github.sepfins.limitguard.port.persistence.LimitRuleRepository
import org.jooq.Condition
import org.jooq.Record
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

class JooqLimitRuleRepository(
    private val context: JooqPersistenceContext,
) : LimitRuleRepository {
    private val table = GeneratedLimitRuleTable.LIMIT_RULE
    private val configuration = GeneratedLimitConfigurationTable.LIMIT_CONFIGURATION

    override fun findById(tenantId: TenantId, id: LimitRuleId): LimitRule? =
        jooqOperation("find limit rule by id") {
            selectRules().where(table.TENANT_ID.eq(tenantId.value)).and(table.ID.eq(id.value)).fetchOne { it.toDomain() }
        }

    override fun findByIdForUpdate(tenantId: TenantId, id: LimitRuleId): LimitRule? {
        context.requireTransaction("locking a limit rule row")
        return jooqOperation("lock limit rule by id") {
            selectRules().where(table.TENANT_ID.eq(tenantId.value)).and(table.ID.eq(id.value))
                .forUpdate().of(table).fetchOne { it.toDomain() }
        }
    }

    override fun findByTargetForUpdate(tenantId: TenantId, target: LimitRuleTarget): LimitRule? {
        context.requireTransaction("locking a limit rule row")
        return jooqOperation("lock limit rule by target") {
            val mandateId = (target.mandateScope as? MandateScope.MandateSpecific)?.mandateId?.value
            selectRules()
                .where(table.TENANT_ID.eq(tenantId.value))
                .and(table.OWNER_PARTY_PROFILE_ID.eq(target.owner.value))
                .and(table.CONFIGURATION_ID.eq(target.configurationId.value))
                .and(mandateId?.let { table.MANDATE_ID.eq(it) } ?: table.MANDATE_ID.isNull)
                .forUpdate().of(table)
                .fetchOne { it.toDomain() }
        }
    }

    override fun findByOwner(
        tenantId: TenantId,
        owner: PartyProfileId,
    ): List<LimitRule> = jooqOperation("find limit rules for owner") {
        findForOwner(tenantId, owner, table.ID.isNotNull)
    }

    override fun findActiveForAuthority(
        tenantId: TenantId,
        owner: PartyProfileId,
        mandateIds: Set<MandateId>,
    ): List<LimitRule> = jooqOperation("find active limit rules for authority") {
        selectRules()
            .where(table.TENANT_ID.eq(tenantId.value))
            .and(table.OWNER_PARTY_PROFILE_ID.eq(owner.value))
            .and(table.IS_ACTIVE.isTrue)
            .and(table.MANDATE_ID.isNull.or(table.MANDATE_ID.`in`(mandateIds.map { it.value })))
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
                .set(table.CONFIGURATION_ID, limitRule.configurationId.value)
                .set(table.MANDATE_ID, limitRule.mandateId())
                .set(table.AMOUNT, limitRule.amount.storedAmount)
                .set(table.AMOUNT_UNLIMITED, limitRule.amount is LimitAmount.Unlimited)
                .set(table.IS_ACTIVE, limitRule.isActive)
                .set(table.VERSION, limitRule.version.value)
                .set(table.METADATA, limitRule.metadata.toJsonb())
            limitRule.createdAt?.let { insert.set(table.CREATED_AT, it.toOffsetDateTime()) }
            limitRule.updatedAt?.let { insert.set(table.UPDATED_AT, it.toOffsetDateTime()) }
            requireNotNull(insert.returning().fetchOne()).toDomain(limitRule.amount.currency)
        }

    /** Only the amount, the active flag, the version, and metadata change; the target is fixed. */
    override fun updateIfVersion(
        tenantId: TenantId,
        limitRule: LimitRule,
        expectedVersion: Version,
    ): LimitRule? = jooqOperation("conditionally update limit rule") {
        require(limitRule.tenantId == tenantId) { "limit rule tenant does not match repository tenant" }
        val update = context.dsl()
            .update(table)
            .set(table.AMOUNT, limitRule.amount.storedAmount)
            .set(table.AMOUNT_UNLIMITED, limitRule.amount is LimitAmount.Unlimited)
            .set(table.IS_ACTIVE, limitRule.isActive)
            .set(table.VERSION, limitRule.version.value)
            .set(table.METADATA, limitRule.metadata.toJsonb())
        limitRule.updatedAt?.let { update.set(table.UPDATED_AT, it.toOffsetDateTime()) }
        update.where(table.TENANT_ID.eq(tenantId.value))
            .and(table.ID.eq(limitRule.id.value))
            .and(table.VERSION.eq(expectedVersion.value))
            .returning()
            .fetchOne()
            ?.toDomain(limitRule.amount.currency)
    }

    private fun findForOwner(tenantId: TenantId, owner: PartyProfileId, condition: Condition): List<LimitRule> =
        selectRules()
            .where(table.TENANT_ID.eq(tenantId.value))
            .and(table.OWNER_PARTY_PROFILE_ID.eq(owner.value))
            .and(condition)
            .orderBy(table.CREATED_AT, table.ID)
            .fetch { it.toDomain() }

    /** A rule's amount is in its configuration's currency, the only currency column. */
    private fun selectRules() = context.dsl()
        .select(table.fields().toList() + configuration.CURRENCY)
        .from(table)
        .join(configuration)
        .on(configuration.TENANT_ID.eq(table.TENANT_ID))
        .and(configuration.ID.eq(table.CONFIGURATION_ID))

    private fun Record.toDomain(): LimitRule =
        toDomain(CurrencyCode.of(requireNotNull(get(configuration.CURRENCY))))

    private fun Record.toDomain(currency: CurrencyCode): LimitRule =
        LimitRule(
            id = LimitRuleId.of(requireNotNull(get(table.ID))),
            tenantId = TenantId.of(requireNotNull(get(table.TENANT_ID))),
            ownerPartyProfileId = PartyProfileId.of(requireNotNull(get(table.OWNER_PARTY_PROFILE_ID))),
            configurationId = LimitConfigurationId.of(requireNotNull(get(table.CONFIGURATION_ID))),
            mandateScope = get(table.MANDATE_ID)?.let { MandateScope.MandateSpecific(MandateId.of(it)) }
                ?: MandateScope.AllOwnerMandates,
            amount = storedLimitAmount(
                get(table.AMOUNT),
                requireNotNull(get(table.AMOUNT_UNLIMITED)),
                currency,
            ),
            isActive = requireNotNull(get(table.IS_ACTIVE)),
            version = Version.of(requireNotNull(get(table.VERSION))),
            metadata = get(table.METADATA).toMetadata(),
            createdAt = get(table.CREATED_AT)?.toInstant(),
            updatedAt = get(table.UPDATED_AT)?.toInstant(),
        )

    private fun LimitRule.mandateId() =
        (mandateScope as? MandateScope.MandateSpecific)?.mandateId?.value
}

private fun Instant.toOffsetDateTime(): OffsetDateTime = OffsetDateTime.ofInstant(this, ZoneOffset.UTC)
