package io.github.sepfins.limitguard.infrastructure.jooq.repository

import io.github.sepfins.limitguard.domain.common.EffectiveWindow
import io.github.sepfins.limitguard.domain.common.Metadata
import io.github.sepfins.limitguard.domain.common.PartyId
import io.github.sepfins.limitguard.domain.common.PartyLegalIdentifierId
import io.github.sepfins.limitguard.domain.common.PartyProfileDelegationId
import io.github.sepfins.limitguard.domain.common.PartyProfileId
import io.github.sepfins.limitguard.domain.common.TenantId
import io.github.sepfins.limitguard.domain.party.FirstName
import io.github.sepfins.limitguard.domain.party.Individual
import io.github.sepfins.limitguard.domain.party.JurisdictionCountry
import io.github.sepfins.limitguard.domain.party.LastName
import io.github.sepfins.limitguard.domain.party.LegalEntity
import io.github.sepfins.limitguard.domain.party.LegalForm
import io.github.sepfins.limitguard.domain.party.LegalIdentifierType
import io.github.sepfins.limitguard.domain.party.LegalIdentifierValue
import io.github.sepfins.limitguard.domain.party.LegalName
import io.github.sepfins.limitguard.domain.party.Party
import io.github.sepfins.limitguard.domain.party.PartyLegalIdentifier
import io.github.sepfins.limitguard.domain.party.PartyProfile
import io.github.sepfins.limitguard.domain.party.PartyProfileDelegation
import io.github.sepfins.limitguard.domain.party.PartyProfileDelegationType
import io.github.sepfins.limitguard.domain.party.PartyType
import io.github.sepfins.limitguard.infrastructure.jooq.JooqPersistenceContext
import io.github.sepfins.limitguard.infrastructure.jooq.jooqField
import io.github.sepfins.limitguard.infrastructure.jooq.jooqOperation
import io.github.sepfins.limitguard.infrastructure.jooq.jooqTable
import io.github.sepfins.limitguard.infrastructure.jooq.postgresEnum
import io.github.sepfins.limitguard.infrastructure.jooq.toJsonb
import io.github.sepfins.limitguard.infrastructure.jooq.toMetadata
import io.github.sepfins.limitguard.port.persistence.IndividualRepository
import io.github.sepfins.limitguard.port.persistence.LegalEntityRepository
import io.github.sepfins.limitguard.port.persistence.PartyLegalIdentifierRepository
import io.github.sepfins.limitguard.port.persistence.PartyProfileDelegationRepository
import io.github.sepfins.limitguard.port.persistence.PartyProfileRepository
import io.github.sepfins.limitguard.port.persistence.PartyRepository
import org.jooq.Field
import org.jooq.JSONB
import org.jooq.Record
import org.jooq.SelectFieldOrAsterisk
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class JooqPartyRepository(
    private val context: JooqPersistenceContext,
) : PartyRepository {
    override fun findById(id: PartyId): Party? = jooqOperation("find party by id") {
        selectParties().where(PartyTable.id.eq(id.value)).fetchOne { it.toParty() }
    }

    override fun insert(party: Party): Party = jooqOperation("insert party") {
        val insert = context.dsl()
            .insertInto(PartyTable.table)
            .set(PartyTable.id, party.id.value)
            .set(PartyTable.partyType, postgresEnum(party.partyType.name, "party_type"))
        party.createdAt?.let { insert.set(PartyTable.createdAt, it) }
        requireNotNull(insert.returning(PartyTable.fields).fetchOne()).toParty()
    }

    override fun update(party: Party): Party = jooqOperation("update party") {
        requireNotNull(
            context.dsl()
                .update(PartyTable.table)
                .set(PartyTable.partyType, postgresEnum(party.partyType.name, "party_type"))
                .where(PartyTable.id.eq(party.id.value))
                .returning(PartyTable.fields)
                .fetchOne()
        ) { "party not found: ${party.id.value}" }.toParty()
    }

    private fun selectParties() = context.dsl().select(PartyTable.fields).from(PartyTable.table)

    private fun Record.toParty(): Party = Party(
        id = PartyId.of(requireNotNull(get(PartyTable.id))),
        partyType = PartyType.valueOf(requireNotNull(get(PartyTable.partyType))),
        createdAt = get(PartyTable.createdAt),
    )
}

class JooqIndividualRepository(
    private val context: JooqPersistenceContext,
) : IndividualRepository {
    override fun findByPartyId(partyId: PartyId): Individual? = jooqOperation("find individual by party id") {
        selectIndividuals().where(IndividualTable.partyId.eq(partyId.value)).fetchOne { it.toIndividual() }
    }

    override fun insert(individual: Individual): Individual = jooqOperation("insert individual") {
        requireNotNull(
            context.dsl()
                .insertInto(IndividualTable.table)
                .set(IndividualTable.partyId, individual.partyId.value)
                .set(IndividualTable.firstName, individual.firstName.value)
                .set(IndividualTable.lastName, individual.lastName.value)
                .set(IndividualTable.birthDate, individual.birthDate)
                .returning(IndividualTable.fields)
                .fetchOne()
        ).toIndividual()
    }

    override fun update(individual: Individual): Individual = jooqOperation("update individual") {
        requireNotNull(
            context.dsl()
                .update(IndividualTable.table)
                .set(IndividualTable.firstName, individual.firstName.value)
                .set(IndividualTable.lastName, individual.lastName.value)
                .set(IndividualTable.birthDate, individual.birthDate)
                .where(IndividualTable.partyId.eq(individual.partyId.value))
                .returning(IndividualTable.fields)
                .fetchOne()
        ) { "individual not found: ${individual.partyId.value}" }.toIndividual()
    }

    private fun selectIndividuals() = context.dsl().select(IndividualTable.fields).from(IndividualTable.table)

    private fun Record.toIndividual(): Individual = Individual(
        partyId = PartyId.of(requireNotNull(get(IndividualTable.partyId))),
        firstName = FirstName.of(requireNotNull(get(IndividualTable.firstName))),
        lastName = LastName.of(requireNotNull(get(IndividualTable.lastName))),
        birthDate = get(IndividualTable.birthDate),
    )
}

class JooqLegalEntityRepository(
    private val context: JooqPersistenceContext,
) : LegalEntityRepository {
    override fun findByPartyId(partyId: PartyId): LegalEntity? = jooqOperation("find legal entity by party id") {
        selectLegalEntities().where(LegalEntityTable.partyId.eq(partyId.value)).fetchOne { it.toLegalEntity() }
    }

    override fun insert(legalEntity: LegalEntity): LegalEntity = jooqOperation("insert legal entity") {
        requireNotNull(
            context.dsl()
                .insertInto(LegalEntityTable.table)
                .set(LegalEntityTable.partyId, legalEntity.partyId.value)
                .set(LegalEntityTable.legalName, legalEntity.legalName.value)
                .set(LegalEntityTable.legalForm, legalEntity.legalForm?.value)
                .returning(LegalEntityTable.fields)
                .fetchOne()
        ).toLegalEntity()
    }

    override fun update(legalEntity: LegalEntity): LegalEntity = jooqOperation("update legal entity") {
        requireNotNull(
            context.dsl()
                .update(LegalEntityTable.table)
                .set(LegalEntityTable.legalName, legalEntity.legalName.value)
                .set(LegalEntityTable.legalForm, legalEntity.legalForm?.value)
                .where(LegalEntityTable.partyId.eq(legalEntity.partyId.value))
                .returning(LegalEntityTable.fields)
                .fetchOne()
        ) { "legal entity not found: ${legalEntity.partyId.value}" }.toLegalEntity()
    }

    private fun selectLegalEntities() = context.dsl().select(LegalEntityTable.fields).from(LegalEntityTable.table)

    private fun Record.toLegalEntity(): LegalEntity = LegalEntity(
        partyId = PartyId.of(requireNotNull(get(LegalEntityTable.partyId))),
        legalName = LegalName.of(requireNotNull(get(LegalEntityTable.legalName))),
        legalForm = get(LegalEntityTable.legalForm)?.let(LegalForm::of),
    )
}

class JooqPartyLegalIdentifierRepository(
    private val context: JooqPersistenceContext,
) : PartyLegalIdentifierRepository {
    override fun findById(id: PartyLegalIdentifierId): PartyLegalIdentifier? =
        jooqOperation("find party legal identifier by id") {
            selectIdentifiers().where(PartyLegalIdentifierTable.id.eq(id.value)).fetchOne { it.toIdentifier() }
        }

    override fun findByPartyId(partyId: PartyId): List<PartyLegalIdentifier> =
        jooqOperation("find legal identifiers by party") {
            selectIdentifiers()
                .where(PartyLegalIdentifierTable.partyId.eq(partyId.value))
                .orderBy(PartyLegalIdentifierTable.createdAt, PartyLegalIdentifierTable.id)
                .fetch { it.toIdentifier() }
        }

    override fun findByValue(
        jurisdictionCountry: JurisdictionCountry,
        identifierType: LegalIdentifierType,
        normalizedIdentifier: LegalIdentifierValue,
    ): PartyLegalIdentifier? = jooqOperation("find party legal identifier by value") {
        selectIdentifiers()
            .where(PartyLegalIdentifierTable.jurisdictionCountry.eq(jurisdictionCountry.value))
            .and(PartyLegalIdentifierTable.identifierType.eq(identifierType.value))
            .and(PartyLegalIdentifierTable.normalizedIdentifier.eq(normalizedIdentifier.value))
            .fetchOne { it.toIdentifier() }
    }

    override fun insert(identifier: PartyLegalIdentifier): PartyLegalIdentifier =
        jooqOperation("insert party legal identifier") {
            val insert = context.dsl()
                .insertInto(PartyLegalIdentifierTable.table)
                .set(PartyLegalIdentifierTable.id, identifier.id.value)
                .set(PartyLegalIdentifierTable.partyId, identifier.partyId.value)
                .set(PartyLegalIdentifierTable.jurisdictionCountry, identifier.jurisdictionCountry.value)
                .set(PartyLegalIdentifierTable.identifierType, identifier.identifierType.value)
                .set(PartyLegalIdentifierTable.normalizedIdentifier, identifier.normalizedIdentifier.value)
                .set(PartyLegalIdentifierTable.metadata, identifier.metadata.toJsonb())
            identifier.createdAt?.let { insert.set(PartyLegalIdentifierTable.createdAt, it) }
            requireNotNull(insert.returning(PartyLegalIdentifierTable.fields).fetchOne()).toIdentifier()
        }

    override fun update(identifier: PartyLegalIdentifier): PartyLegalIdentifier =
        jooqOperation("update party legal identifier") {
            requireNotNull(
                context.dsl()
                    .update(PartyLegalIdentifierTable.table)
                    .set(PartyLegalIdentifierTable.partyId, identifier.partyId.value)
                    .set(PartyLegalIdentifierTable.jurisdictionCountry, identifier.jurisdictionCountry.value)
                    .set(PartyLegalIdentifierTable.identifierType, identifier.identifierType.value)
                    .set(PartyLegalIdentifierTable.normalizedIdentifier, identifier.normalizedIdentifier.value)
                    .set(PartyLegalIdentifierTable.metadata, identifier.metadata.toJsonb())
                    .where(PartyLegalIdentifierTable.id.eq(identifier.id.value))
                    .returning(PartyLegalIdentifierTable.fields)
                    .fetchOne()
            ) { "party legal identifier not found: ${identifier.id.value}" }.toIdentifier()
        }

    private fun selectIdentifiers() = context.dsl()
        .select(PartyLegalIdentifierTable.fields)
        .from(PartyLegalIdentifierTable.table)

    private fun Record.toIdentifier(): PartyLegalIdentifier = PartyLegalIdentifier(
        id = PartyLegalIdentifierId.of(requireNotNull(get(PartyLegalIdentifierTable.id))),
        partyId = PartyId.of(requireNotNull(get(PartyLegalIdentifierTable.partyId))),
        jurisdictionCountry = JurisdictionCountry.of(
            requireNotNull(get(PartyLegalIdentifierTable.jurisdictionCountry))
        ),
        identifierType = LegalIdentifierType.of(requireNotNull(get(PartyLegalIdentifierTable.identifierType))),
        normalizedIdentifier = LegalIdentifierValue.of(
            requireNotNull(get(PartyLegalIdentifierTable.normalizedIdentifier))
        ),
        metadata = get(PartyLegalIdentifierTable.metadata).toMetadata(),
        createdAt = get(PartyLegalIdentifierTable.createdAt),
    )
}

class JooqPartyProfileRepository(
    private val context: JooqPersistenceContext,
) : PartyProfileRepository {
    override fun findById(tenantId: TenantId, id: PartyProfileId): PartyProfile? =
        jooqOperation("find party profile by id") {
            selectProfiles()
                .where(PartyProfileTable.tenantId.eq(tenantId.value))
                .and(PartyProfileTable.id.eq(id.value))
                .fetchOne { it.toProfile() }
        }

    override fun findByPartyId(tenantId: TenantId, partyId: PartyId): List<PartyProfile> =
        jooqOperation("find party profiles by party") {
            selectProfiles()
                .where(PartyProfileTable.tenantId.eq(tenantId.value))
                .and(PartyProfileTable.partyId.eq(partyId.value))
                .orderBy(PartyProfileTable.createdAt, PartyProfileTable.id)
                .fetch { it.toProfile() }
        }

    override fun insert(tenantId: TenantId, partyProfile: PartyProfile): PartyProfile =
        jooqOperation("insert party profile") {
            require(partyProfile.tenantId == tenantId) { "party profile tenant does not match repository tenant" }
            val insert = context.dsl()
                .insertInto(PartyProfileTable.table)
                .set(PartyProfileTable.id, partyProfile.id.value)
                .set(PartyProfileTable.partyId, partyProfile.partyId.value)
                .set(PartyProfileTable.tenantId, tenantId.value)
                .set(PartyProfileTable.metadata, partyProfile.metadata.toJsonb())
            partyProfile.createdAt?.let { insert.set(PartyProfileTable.createdAt, it) }
            requireNotNull(insert.returning(PartyProfileTable.fields).fetchOne()).toProfile()
        }

    override fun update(tenantId: TenantId, partyProfile: PartyProfile): PartyProfile =
        jooqOperation("update party profile") {
            require(partyProfile.tenantId == tenantId) { "party profile tenant does not match repository tenant" }
            requireNotNull(
                context.dsl()
                    .update(PartyProfileTable.table)
                    .set(PartyProfileTable.partyId, partyProfile.partyId.value)
                    .set(PartyProfileTable.metadata, partyProfile.metadata.toJsonb())
                    .where(PartyProfileTable.tenantId.eq(tenantId.value))
                    .and(PartyProfileTable.id.eq(partyProfile.id.value))
                    .returning(PartyProfileTable.fields)
                    .fetchOne()
            ) { "party profile not found: ${partyProfile.id.value}" }.toProfile()
        }

    private fun selectProfiles() = context.dsl().select(PartyProfileTable.fields).from(PartyProfileTable.table)

    private fun Record.toProfile(): PartyProfile = PartyProfile(
        id = PartyProfileId.of(requireNotNull(get(PartyProfileTable.id))),
        partyId = PartyId.of(requireNotNull(get(PartyProfileTable.partyId))),
        tenantId = TenantId.of(requireNotNull(get(PartyProfileTable.tenantId))),
        createdAt = get(PartyProfileTable.createdAt),
        metadata = get(PartyProfileTable.metadata).toMetadata(),
    )
}

class JooqPartyProfileDelegationRepository(
    private val context: JooqPersistenceContext,
) : PartyProfileDelegationRepository {
    override fun findById(tenantId: TenantId, id: PartyProfileDelegationId): PartyProfileDelegation? =
        jooqOperation("find party profile delegation by id") {
            selectDelegations()
                .where(PartyProfileDelegationTable.tenantId.eq(tenantId.value))
                .and(PartyProfileDelegationTable.id.eq(id.value))
                .fetchOne { it.toDelegation() }
        }

    override fun findByOwner(
        tenantId: TenantId,
        ownerPartyProfileId: PartyProfileId,
    ): List<PartyProfileDelegation> = jooqOperation("find party profile delegations by owner") {
        selectDelegations()
            .where(PartyProfileDelegationTable.tenantId.eq(tenantId.value))
            .and(PartyProfileDelegationTable.ownerPartyProfileId.eq(ownerPartyProfileId.value))
            .orderBy(PartyProfileDelegationTable.createdAt, PartyProfileDelegationTable.id)
            .fetch { it.toDelegation() }
    }

    override fun insert(tenantId: TenantId, delegation: PartyProfileDelegation): PartyProfileDelegation =
        jooqOperation("insert party profile delegation") {
            require(delegation.tenantId == tenantId) {
                "party profile delegation tenant does not match repository tenant"
            }
            val insert = context.dsl()
                .insertInto(PartyProfileDelegationTable.table)
                .set(PartyProfileDelegationTable.id, delegation.id.value)
                .set(PartyProfileDelegationTable.tenantId, tenantId.value)
                .set(PartyProfileDelegationTable.ownerPartyProfileId, delegation.ownerPartyProfileId.value)
                .set(PartyProfileDelegationTable.delegatePartyProfileId, delegation.delegatePartyProfileId.value)
                .set(
                    PartyProfileDelegationTable.delegationType,
                    postgresEnum(delegation.delegationType.name, "party_profile_delegation_type"),
                )
                .set(PartyProfileDelegationTable.startsAt, delegation.effectiveWindow.startsAt)
                .set(PartyProfileDelegationTable.endsAt, delegation.effectiveWindow.endsAt)
                .set(PartyProfileDelegationTable.metadata, delegation.metadata.toJsonb())
            delegation.createdAt?.let { insert.set(PartyProfileDelegationTable.createdAt, it) }
            requireNotNull(insert.returning(PartyProfileDelegationTable.fields).fetchOne()).toDelegation()
        }

    override fun update(tenantId: TenantId, delegation: PartyProfileDelegation): PartyProfileDelegation =
        jooqOperation("update party profile delegation") {
            require(delegation.tenantId == tenantId) {
                "party profile delegation tenant does not match repository tenant"
            }
            requireNotNull(
                context.dsl()
                    .update(PartyProfileDelegationTable.table)
                    .set(PartyProfileDelegationTable.ownerPartyProfileId, delegation.ownerPartyProfileId.value)
                    .set(PartyProfileDelegationTable.delegatePartyProfileId, delegation.delegatePartyProfileId.value)
                    .set(
                        PartyProfileDelegationTable.delegationType,
                        postgresEnum(delegation.delegationType.name, "party_profile_delegation_type"),
                    )
                    .set(PartyProfileDelegationTable.startsAt, delegation.effectiveWindow.startsAt)
                    .set(PartyProfileDelegationTable.endsAt, delegation.effectiveWindow.endsAt)
                    .set(PartyProfileDelegationTable.metadata, delegation.metadata.toJsonb())
                    .where(PartyProfileDelegationTable.tenantId.eq(tenantId.value))
                    .and(PartyProfileDelegationTable.id.eq(delegation.id.value))
                    .returning(PartyProfileDelegationTable.fields)
                    .fetchOne()
            ) { "party profile delegation not found: ${delegation.id.value}" }.toDelegation()
        }

    private fun selectDelegations() = context.dsl()
        .select(PartyProfileDelegationTable.fields)
        .from(PartyProfileDelegationTable.table)

    private fun Record.toDelegation(): PartyProfileDelegation = PartyProfileDelegation(
        id = PartyProfileDelegationId.of(requireNotNull(get(PartyProfileDelegationTable.id))),
        tenantId = TenantId.of(requireNotNull(get(PartyProfileDelegationTable.tenantId))),
        ownerPartyProfileId = PartyProfileId.of(
            requireNotNull(get(PartyProfileDelegationTable.ownerPartyProfileId))
        ),
        delegatePartyProfileId = PartyProfileId.of(
            requireNotNull(get(PartyProfileDelegationTable.delegatePartyProfileId))
        ),
        delegationType = PartyProfileDelegationType.valueOf(
            requireNotNull(get(PartyProfileDelegationTable.delegationType))
        ),
        effectiveWindow = EffectiveWindow(
            startsAt = requireNotNull(get(PartyProfileDelegationTable.startsAt)),
            endsAt = get(PartyProfileDelegationTable.endsAt),
        ),
        metadata = get(PartyProfileDelegationTable.metadata).toMetadata(),
        createdAt = get(PartyProfileDelegationTable.createdAt),
    )
}

private object PartyTable {
    const val NAME = "party"
    val table = jooqTable(NAME)
    val id: Field<UUID> = jooqField(NAME, "id", UUID::class.java)
    val partyType: Field<String> = jooqField(NAME, "party_type", String::class.java)
    val createdAt: Field<Instant> = jooqField(NAME, "created_at", Instant::class.java)
    val fields: List<SelectFieldOrAsterisk> = listOf(id, partyType, createdAt)
}

private object IndividualTable {
    const val NAME = "individual"
    val table = jooqTable(NAME)
    val partyId: Field<UUID> = jooqField(NAME, "party_id", UUID::class.java)
    val firstName: Field<String> = jooqField(NAME, "first_name", String::class.java)
    val lastName: Field<String> = jooqField(NAME, "last_name", String::class.java)
    val birthDate: Field<LocalDate> = jooqField(NAME, "birth_date", LocalDate::class.java)
    val fields: List<SelectFieldOrAsterisk> = listOf(partyId, firstName, lastName, birthDate)
}

private object LegalEntityTable {
    const val NAME = "legal_entity"
    val table = jooqTable(NAME)
    val partyId: Field<UUID> = jooqField(NAME, "party_id", UUID::class.java)
    val legalName: Field<String> = jooqField(NAME, "legal_name", String::class.java)
    val legalForm: Field<String> = jooqField(NAME, "legal_form", String::class.java)
    val fields: List<SelectFieldOrAsterisk> = listOf(partyId, legalName, legalForm)
}

private object PartyLegalIdentifierTable {
    const val NAME = "party_legal_identifier"
    val table = jooqTable(NAME)
    val id: Field<UUID> = jooqField(NAME, "id", UUID::class.java)
    val partyId: Field<UUID> = jooqField(NAME, "party_id", UUID::class.java)
    val jurisdictionCountry: Field<String> = jooqField(NAME, "jurisdiction_country", String::class.java)
    val identifierType: Field<String> = jooqField(NAME, "identifier_type", String::class.java)
    val normalizedIdentifier: Field<String> = jooqField(NAME, "normalized_identifier", String::class.java)
    val metadata: Field<JSONB> = jooqField(NAME, "metadata", JSONB::class.java)
    val createdAt: Field<Instant> = jooqField(NAME, "created_at", Instant::class.java)
    val fields: List<SelectFieldOrAsterisk> =
        listOf(id, partyId, jurisdictionCountry, identifierType, normalizedIdentifier, metadata, createdAt)
}

private object PartyProfileTable {
    const val NAME = "party_profile"
    val table = jooqTable(NAME)
    val id: Field<UUID> = jooqField(NAME, "id", UUID::class.java)
    val partyId: Field<UUID> = jooqField(NAME, "party_id", UUID::class.java)
    val tenantId: Field<UUID> = jooqField(NAME, "tenant_id", UUID::class.java)
    val metadata: Field<JSONB> = jooqField(NAME, "metadata", JSONB::class.java)
    val createdAt: Field<Instant> = jooqField(NAME, "created_at", Instant::class.java)
    val fields: List<SelectFieldOrAsterisk> = listOf(id, partyId, tenantId, metadata, createdAt)
}

private object PartyProfileDelegationTable {
    const val NAME = "party_profile_delegation"
    val table = jooqTable(NAME)
    val id: Field<UUID> = jooqField(NAME, "id", UUID::class.java)
    val tenantId: Field<UUID> = jooqField(NAME, "tenant_id", UUID::class.java)
    val ownerPartyProfileId: Field<UUID> = jooqField(NAME, "owner_party_profile_id", UUID::class.java)
    val delegatePartyProfileId: Field<UUID> = jooqField(NAME, "delegate_party_profile_id", UUID::class.java)
    val delegationType: Field<String> = jooqField(NAME, "delegation_type", String::class.java)
    val startsAt: Field<Instant> = jooqField(NAME, "starts_at", Instant::class.java)
    val endsAt: Field<Instant> = jooqField(NAME, "ends_at", Instant::class.java)
    val metadata: Field<JSONB> = jooqField(NAME, "metadata", JSONB::class.java)
    val createdAt: Field<Instant> = jooqField(NAME, "created_at", Instant::class.java)
    val fields: List<SelectFieldOrAsterisk> = listOf(
        id,
        tenantId,
        ownerPartyProfileId,
        delegatePartyProfileId,
        delegationType,
        startsAt,
        endsAt,
        metadata,
        createdAt,
    )
}
