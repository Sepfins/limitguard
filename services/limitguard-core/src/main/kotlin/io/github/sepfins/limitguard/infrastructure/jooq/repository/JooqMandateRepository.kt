package io.github.sepfins.limitguard.infrastructure.jooq.repository

import io.github.sepfins.limitguard.domain.common.EffectiveWindow
import io.github.sepfins.limitguard.domain.common.FundingSourceGroupId
import io.github.sepfins.limitguard.domain.common.FundingSourceId
import io.github.sepfins.limitguard.domain.common.MandateId
import io.github.sepfins.limitguard.domain.common.PartyProfileDelegationId
import io.github.sepfins.limitguard.domain.common.PartyProfileId
import io.github.sepfins.limitguard.domain.common.TenantId
import io.github.sepfins.limitguard.domain.common.Version
import io.github.sepfins.limitguard.domain.funding.FundingSourceScope
import io.github.sepfins.limitguard.domain.mandate.Mandate
import io.github.sepfins.limitguard.domain.mandate.MandateName
import io.github.sepfins.limitguard.infrastructure.jooq.JooqPersistenceContext
import io.github.sepfins.limitguard.infrastructure.jooq.eqPostgresEnum
import io.github.sepfins.limitguard.infrastructure.jooq.jooqField
import io.github.sepfins.limitguard.infrastructure.jooq.jooqOperation
import io.github.sepfins.limitguard.infrastructure.jooq.jooqTable
import io.github.sepfins.limitguard.infrastructure.jooq.postgresEnum
import io.github.sepfins.limitguard.infrastructure.jooq.toJsonb
import io.github.sepfins.limitguard.infrastructure.jooq.toMetadata
import io.github.sepfins.limitguard.port.persistence.MandateRepository
import org.jooq.Condition
import org.jooq.Field
import org.jooq.JSONB
import org.jooq.Record
import org.jooq.SelectFieldOrAsterisk
import org.jooq.impl.DSL
import java.time.Instant
import java.util.UUID

class JooqMandateRepository(
    private val context: JooqPersistenceContext,
) : MandateRepository {
    override fun findActiveForAssignee(
        tenantId: TenantId,
        assignee: PartyProfileId,
        at: Instant,
    ): List<Mandate> = findActive(
        tenantId,
        at,
        MandateTable.assigneePartyProfileId.eq(assignee.value),
        "find active mandates for assignee",
    )

    override fun findActiveForOwner(
        tenantId: TenantId,
        owner: PartyProfileId,
        at: Instant,
    ): List<Mandate> = findActive(
        tenantId,
        at,
        MandateTable.ownerPartyProfileId.eq(owner.value),
        "find active mandates for owner",
    )

    override fun findActiveForOwnerWithScope(
        tenantId: TenantId,
        owner: PartyProfileId,
        at: Instant,
        scope: FundingSourceScope,
    ): List<Mandate> = findActive(
        tenantId,
        at,
        MandateTable.ownerPartyProfileId.eq(owner.value).and(scopeCondition(scope)),
        "find active mandates for owner and scope",
    )

    override fun findById(tenantId: TenantId, id: MandateId): Mandate? =
        jooqOperation("find mandate by id") {
            selectMandates()
                .where(MandateTable.tenantId.eq(tenantId.value))
                .and(MandateTable.id.eq(id.value))
                .fetchOne { it.toDomain() }
        }

    override fun insert(tenantId: TenantId, mandate: Mandate): Mandate = jooqOperation("insert mandate") {
        require(mandate.tenantId == tenantId) { "mandate tenant does not match repository tenant" }
        val insert = context.dsl()
            .insertInto(MandateTable.table)
            .set(MandateTable.id, mandate.id.value)
            .set(MandateTable.tenantId, tenantId.value)
            .set(MandateTable.ownerPartyProfileId, mandate.ownerPartyProfileId.value)
            .set(MandateTable.assigneePartyProfileId, mandate.assigneePartyProfileId.value)
            .set(MandateTable.authorityDelegationId, mandate.authorityDelegationId?.value)
            .set(MandateTable.name, mandate.name.value)
            .set(MandateTable.description, mandate.description)
            .set(MandateTable.fundingScope, postgresEnum(mandate.fundingScope.name(), "limit_funding_scope"))
            .set(MandateTable.fundingSourceId, mandate.fundingScope.fundingSourceId())
            .set(MandateTable.fundingSourceGroupId, mandate.fundingScope.fundingSourceGroupId())
            .set(MandateTable.isActive, mandate.isActive)
            .set(MandateTable.version, mandate.version.value)
            .set(MandateTable.startsAt, mandate.effectiveWindow.startsAt)
            .set(MandateTable.endsAt, mandate.effectiveWindow.endsAt)
            .set(MandateTable.metadata, mandate.metadata.toJsonb())
        mandate.createdAt?.let { insert.set(MandateTable.createdAt, it) }
        mandate.updatedAt?.let { insert.set(MandateTable.updatedAt, it) }
        requireNotNull(insert.returning(MandateTable.fields).fetchOne()).toDomain()
    }

    override fun update(tenantId: TenantId, mandate: Mandate): Mandate = jooqOperation("update mandate") {
        require(mandate.tenantId == tenantId) { "mandate tenant does not match repository tenant" }
        val update = context.dsl()
            .update(MandateTable.table)
            .set(MandateTable.ownerPartyProfileId, mandate.ownerPartyProfileId.value)
            .set(MandateTable.assigneePartyProfileId, mandate.assigneePartyProfileId.value)
            .set(MandateTable.authorityDelegationId, mandate.authorityDelegationId?.value)
            .set(MandateTable.name, mandate.name.value)
            .set(MandateTable.description, mandate.description)
            .set(MandateTable.fundingScope, postgresEnum(mandate.fundingScope.name(), "limit_funding_scope"))
            .set(MandateTable.fundingSourceId, mandate.fundingScope.fundingSourceId())
            .set(MandateTable.fundingSourceGroupId, mandate.fundingScope.fundingSourceGroupId())
            .set(MandateTable.isActive, mandate.isActive)
            .set(MandateTable.version, mandate.version.value)
            .set(MandateTable.startsAt, mandate.effectiveWindow.startsAt)
            .set(MandateTable.endsAt, mandate.effectiveWindow.endsAt)
            .set(MandateTable.metadata, mandate.metadata.toJsonb())
        mandate.updatedAt?.let { update.set(MandateTable.updatedAt, it) }
        requireNotNull(
            update.where(MandateTable.tenantId.eq(tenantId.value))
                .and(MandateTable.id.eq(mandate.id.value))
                .returning(MandateTable.fields)
                .fetchOne()
        ) { "mandate not found: ${mandate.id.value}" }.toDomain()
    }

    private fun findActive(
        tenantId: TenantId,
        at: Instant,
        condition: Condition,
        operation: String,
    ): List<Mandate> = jooqOperation(operation) {
        selectMandates()
            .where(MandateTable.tenantId.eq(tenantId.value))
            .and(MandateTable.isActive.isTrue)
            .and(MandateTable.startsAt.le(at))
            .and(MandateTable.endsAt.isNull.or(MandateTable.endsAt.gt(at)))
            .and(condition)
            .orderBy(MandateTable.createdAt, MandateTable.id)
            .fetch { it.toDomain() }
    }

    private fun scopeCondition(scope: FundingSourceScope): Condition =
        MandateTable.fundingScope.eqPostgresEnum(scope.name(), "limit_funding_scope")
            .and(
                when (scope) {
                    FundingSourceScope.AllOwnerSources -> DSL.trueCondition()
                    is FundingSourceScope.FundingSourceSpecific ->
                        MandateTable.fundingSourceId.eq(scope.fundingSourceId.value)
                    is FundingSourceScope.FundingSourceGroupSpecific ->
                        MandateTable.fundingSourceGroupId.eq(scope.fundingSourceGroupId.value)
                }
            )

    private fun selectMandates() = context.dsl().select(MandateTable.fields).from(MandateTable.table)

    private fun Record.toDomain(): Mandate {
        val fundingScope = when (val scope = requireNotNull(get(MandateTable.fundingScope))) {
            "ALL_OWNER_SOURCES" -> FundingSourceScope.AllOwnerSources
            "FUNDING_SOURCE" -> FundingSourceScope.FundingSourceSpecific(
                FundingSourceId.of(requireNotNull(get(MandateTable.fundingSourceId)))
            )
            "FUNDING_SOURCE_GROUP" -> FundingSourceScope.FundingSourceGroupSpecific(
                FundingSourceGroupId.of(requireNotNull(get(MandateTable.fundingSourceGroupId)))
            )
            else -> error("unsupported mandate funding scope: $scope")
        }
        return Mandate(
            id = MandateId.of(requireNotNull(get(MandateTable.id))),
            tenantId = TenantId.of(requireNotNull(get(MandateTable.tenantId))),
            ownerPartyProfileId = PartyProfileId.of(requireNotNull(get(MandateTable.ownerPartyProfileId))),
            assigneePartyProfileId = PartyProfileId.of(
                requireNotNull(get(MandateTable.assigneePartyProfileId))
            ),
            authorityDelegationId = get(MandateTable.authorityDelegationId)
                ?.let(PartyProfileDelegationId::of),
            name = MandateName.of(requireNotNull(get(MandateTable.name))),
            description = get(MandateTable.description),
            fundingScope = fundingScope,
            isActive = requireNotNull(get(MandateTable.isActive)),
            version = Version.of(requireNotNull(get(MandateTable.version))),
            effectiveWindow = EffectiveWindow(
                startsAt = requireNotNull(get(MandateTable.startsAt)),
                endsAt = get(MandateTable.endsAt),
            ),
            metadata = get(MandateTable.metadata).toMetadata(),
            createdAt = get(MandateTable.createdAt),
            updatedAt = get(MandateTable.updatedAt),
        )
    }

    private fun FundingSourceScope.name(): String = when (this) {
        FundingSourceScope.AllOwnerSources -> "ALL_OWNER_SOURCES"
        is FundingSourceScope.FundingSourceSpecific -> "FUNDING_SOURCE"
        is FundingSourceScope.FundingSourceGroupSpecific -> "FUNDING_SOURCE_GROUP"
    }

    private fun FundingSourceScope.fundingSourceId(): UUID? =
        (this as? FundingSourceScope.FundingSourceSpecific)?.fundingSourceId?.value

    private fun FundingSourceScope.fundingSourceGroupId(): UUID? =
        (this as? FundingSourceScope.FundingSourceGroupSpecific)?.fundingSourceGroupId?.value
}

private object MandateTable {
    const val NAME = "mandate"
    val table = jooqTable(NAME)
    val id: Field<UUID> = jooqField(NAME, "id", UUID::class.java)
    val tenantId: Field<UUID> = jooqField(NAME, "tenant_id", UUID::class.java)
    val ownerPartyProfileId: Field<UUID> = jooqField(NAME, "owner_party_profile_id", UUID::class.java)
    val assigneePartyProfileId: Field<UUID> = jooqField(NAME, "assignee_party_profile_id", UUID::class.java)
    val authorityDelegationId: Field<UUID> = jooqField(NAME, "authority_delegation_id", UUID::class.java)
    val name: Field<String> = jooqField(NAME, "name", String::class.java)
    val description: Field<String> = jooqField(NAME, "description", String::class.java)
    val fundingScope: Field<String> = jooqField(NAME, "funding_scope", String::class.java)
    val fundingSourceId: Field<UUID> = jooqField(NAME, "funding_source_id", UUID::class.java)
    val fundingSourceGroupId: Field<UUID> = jooqField(NAME, "funding_source_group_id", UUID::class.java)
    val isActive: Field<Boolean> = jooqField(NAME, "is_active", Boolean::class.java)
    val version: Field<Int> = jooqField(NAME, "version", Int::class.java)
    val startsAt: Field<Instant> = jooqField(NAME, "starts_at", Instant::class.java)
    val endsAt: Field<Instant> = jooqField(NAME, "ends_at", Instant::class.java)
    val metadata: Field<JSONB> = jooqField(NAME, "metadata", JSONB::class.java)
    val createdAt: Field<Instant> = jooqField(NAME, "created_at", Instant::class.java)
    val updatedAt: Field<Instant> = jooqField(NAME, "updated_at", Instant::class.java)
    val fields: List<SelectFieldOrAsterisk> = listOf(
        id,
        tenantId,
        ownerPartyProfileId,
        assigneePartyProfileId,
        authorityDelegationId,
        name,
        description,
        fundingScope,
        fundingSourceId,
        fundingSourceGroupId,
        isActive,
        version,
        startsAt,
        endsAt,
        metadata,
        createdAt,
        updatedAt,
    )
}
