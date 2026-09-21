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
import io.github.sepfins.limitguard.infrastructure.jooq.jooqOperation
import io.github.sepfins.limitguard.infrastructure.jooq.toJsonb
import io.github.sepfins.limitguard.infrastructure.jooq.toMetadata
import io.github.sepfins.limitguard.infrastructure.jooq.generated.enums.LimitFundingScope as GeneratedLimitFundingScope
import io.github.sepfins.limitguard.infrastructure.jooq.generated.tables.Mandate as GeneratedMandateTable
import io.github.sepfins.limitguard.port.persistence.MandateRepository
import org.jooq.Condition
import org.jooq.Record
import org.jooq.SelectFieldOrAsterisk
import org.jooq.impl.DSL
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
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
            .set(MandateTable.fundingScope, GeneratedLimitFundingScope.valueOf(mandate.fundingScope.name()))
            .set(MandateTable.fundingSourceId, mandate.fundingScope.fundingSourceId())
            .set(MandateTable.fundingSourceGroupId, mandate.fundingScope.fundingSourceGroupId())
            .set(MandateTable.isActive, mandate.isActive)
            .set(MandateTable.version, mandate.version.value)
            .set(MandateTable.startsAt, mandate.effectiveWindow.startsAt.toOffsetDateTime())
            .set(MandateTable.endsAt, mandate.effectiveWindow.endsAt?.toOffsetDateTime())
            .set(MandateTable.metadata, mandate.metadata.toJsonb())
        mandate.createdAt?.let { insert.set(MandateTable.createdAt, it.toOffsetDateTime()) }
        mandate.updatedAt?.let { insert.set(MandateTable.updatedAt, it.toOffsetDateTime()) }
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
            .set(MandateTable.fundingScope, GeneratedLimitFundingScope.valueOf(mandate.fundingScope.name()))
            .set(MandateTable.fundingSourceId, mandate.fundingScope.fundingSourceId())
            .set(MandateTable.fundingSourceGroupId, mandate.fundingScope.fundingSourceGroupId())
            .set(MandateTable.isActive, mandate.isActive)
            .set(MandateTable.version, mandate.version.value)
            .set(MandateTable.startsAt, mandate.effectiveWindow.startsAt.toOffsetDateTime())
            .set(MandateTable.endsAt, mandate.effectiveWindow.endsAt?.toOffsetDateTime())
            .set(MandateTable.metadata, mandate.metadata.toJsonb())
        mandate.updatedAt?.let { update.set(MandateTable.updatedAt, it.toOffsetDateTime()) }
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
            .and(MandateTable.startsAt.le(at.toOffsetDateTime()))
            .and(MandateTable.endsAt.isNull.or(MandateTable.endsAt.gt(at.toOffsetDateTime())))
            .and(condition)
            .orderBy(MandateTable.createdAt, MandateTable.id)
            .fetch { it.toDomain() }
    }

    private fun scopeCondition(scope: FundingSourceScope): Condition =
        MandateTable.fundingScope.eq(GeneratedLimitFundingScope.valueOf(scope.name()))
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
        val fundingScope = when (val scope = requireNotNull(get(MandateTable.fundingScope)).name) {
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
                startsAt = requireNotNull(get(MandateTable.startsAt)).toInstant(),
                endsAt = get(MandateTable.endsAt)?.toInstant(),
            ),
            metadata = get(MandateTable.metadata).toMetadata(),
            createdAt = get(MandateTable.createdAt)?.toInstant(),
            updatedAt = get(MandateTable.updatedAt)?.toInstant(),
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
    val table = GeneratedMandateTable.MANDATE
    val id = table.ID
    val tenantId = table.TENANT_ID
    val ownerPartyProfileId = table.OWNER_PARTY_PROFILE_ID
    val assigneePartyProfileId = table.ASSIGNEE_PARTY_PROFILE_ID
    val authorityDelegationId = table.AUTHORITY_DELEGATION_ID
    val name = table.NAME
    val description = table.DESCRIPTION
    val fundingScope = table.FUNDING_SCOPE
    val fundingSourceId = table.FUNDING_SOURCE_ID
    val fundingSourceGroupId = table.FUNDING_SOURCE_GROUP_ID
    val isActive = table.IS_ACTIVE
    val version = table.VERSION
    val startsAt = table.STARTS_AT
    val endsAt = table.ENDS_AT
    val metadata = table.METADATA
    val createdAt = table.CREATED_AT
    val updatedAt = table.UPDATED_AT
    val fields: List<SelectFieldOrAsterisk> = table.fields().toList()
}

private fun Instant.toOffsetDateTime(): OffsetDateTime = OffsetDateTime.ofInstant(this, ZoneOffset.UTC)
