package io.github.sepfins.limitguard.infrastructure.jooq.repository

import io.github.sepfins.limitguard.domain.common.EntityIdentifierId
import io.github.sepfins.limitguard.domain.common.IdentifierSchemeId
import io.github.sepfins.limitguard.domain.common.TenantId
import io.github.sepfins.limitguard.domain.identifier.EntityIdentifier
import io.github.sepfins.limitguard.domain.identifier.EntityType
import io.github.sepfins.limitguard.domain.identifier.IdentifierScheme
import io.github.sepfins.limitguard.domain.identifier.IdentifierValue
import io.github.sepfins.limitguard.domain.identifier.SchemeName
import io.github.sepfins.limitguard.infrastructure.jooq.JooqPersistenceContext
import io.github.sepfins.limitguard.infrastructure.jooq.jooqField
import io.github.sepfins.limitguard.infrastructure.jooq.jooqOperation
import io.github.sepfins.limitguard.infrastructure.jooq.jooqTable
import io.github.sepfins.limitguard.port.persistence.EntityIdentifierRepository
import io.github.sepfins.limitguard.port.persistence.IdentifierSchemeRepository
import org.jooq.Field
import org.jooq.Record
import org.jooq.SelectFieldOrAsterisk
import java.time.Instant
import java.util.UUID

class JooqIdentifierSchemeRepository(
    private val context: JooqPersistenceContext,
) : IdentifierSchemeRepository {
    override fun findById(tenantId: TenantId, id: IdentifierSchemeId): IdentifierScheme? =
        jooqOperation("find identifier scheme by id") {
            selectSchemes()
                .where(IdentifierSchemeTable.tenantId.eq(tenantId.value))
                .and(IdentifierSchemeTable.id.eq(id.value))
                .fetchOne { it.toDomain() }
        }

    override fun findByName(
        tenantId: TenantId,
        entityType: EntityType,
        schemeName: SchemeName,
    ): IdentifierScheme? = jooqOperation("find identifier scheme by name") {
        selectSchemes()
            .where(IdentifierSchemeTable.tenantId.eq(tenantId.value))
            .and(IdentifierSchemeTable.entityType.eq(entityType.value))
            .and(IdentifierSchemeTable.schemeName.eq(schemeName.value))
            .fetchOne { it.toDomain() }
    }

    override fun findPrimary(tenantId: TenantId, entityType: EntityType): IdentifierScheme? =
        jooqOperation("find primary identifier scheme") {
            selectSchemes()
                .where(IdentifierSchemeTable.tenantId.eq(tenantId.value))
                .and(IdentifierSchemeTable.entityType.eq(entityType.value))
                .and(IdentifierSchemeTable.isPrimary.isTrue)
                .fetchOne { it.toDomain() }
        }

    override fun insert(tenantId: TenantId, scheme: IdentifierScheme): IdentifierScheme =
        jooqOperation("insert identifier scheme") {
            require(scheme.tenantId == tenantId) { "identifier scheme tenant does not match repository tenant" }
            val insert = context.dsl()
                .insertInto(IdentifierSchemeTable.table)
                .set(IdentifierSchemeTable.id, scheme.id.value)
                .set(IdentifierSchemeTable.tenantId, tenantId.value)
                .set(IdentifierSchemeTable.entityType, scheme.entityType.value)
                .set(IdentifierSchemeTable.schemeName, scheme.schemeName.value)
                .set(IdentifierSchemeTable.formatRegex, scheme.formatRegex)
                .set(IdentifierSchemeTable.maxLength, scheme.maxLength)
                .set(IdentifierSchemeTable.isPrimary, scheme.isPrimary)
            scheme.createdAt?.let { insert.set(IdentifierSchemeTable.createdAt, it) }
            requireNotNull(insert.returning(IdentifierSchemeTable.fields).fetchOne()).toDomain()
        }

    override fun update(tenantId: TenantId, scheme: IdentifierScheme): IdentifierScheme =
        jooqOperation("update identifier scheme") {
            require(scheme.tenantId == tenantId) { "identifier scheme tenant does not match repository tenant" }
            requireNotNull(
                context.dsl()
                    .update(IdentifierSchemeTable.table)
                    .set(IdentifierSchemeTable.entityType, scheme.entityType.value)
                    .set(IdentifierSchemeTable.schemeName, scheme.schemeName.value)
                    .set(IdentifierSchemeTable.formatRegex, scheme.formatRegex)
                    .set(IdentifierSchemeTable.maxLength, scheme.maxLength)
                    .set(IdentifierSchemeTable.isPrimary, scheme.isPrimary)
                    .where(IdentifierSchemeTable.tenantId.eq(tenantId.value))
                    .and(IdentifierSchemeTable.id.eq(scheme.id.value))
                    .returning(IdentifierSchemeTable.fields)
                    .fetchOne()
            ) { "identifier scheme not found: ${scheme.id.value}" }.toDomain()
        }

    private fun selectSchemes() = context.dsl()
        .select(IdentifierSchemeTable.fields)
        .from(IdentifierSchemeTable.table)

    private fun Record.toDomain(): IdentifierScheme = IdentifierScheme(
        id = IdentifierSchemeId.of(requireNotNull(get(IdentifierSchemeTable.id))),
        tenantId = TenantId.of(requireNotNull(get(IdentifierSchemeTable.tenantId))),
        entityType = EntityType.of(requireNotNull(get(IdentifierSchemeTable.entityType))),
        schemeName = SchemeName.of(requireNotNull(get(IdentifierSchemeTable.schemeName))),
        formatRegex = get(IdentifierSchemeTable.formatRegex),
        maxLength = get(IdentifierSchemeTable.maxLength),
        isPrimary = requireNotNull(get(IdentifierSchemeTable.isPrimary)),
        createdAt = get(IdentifierSchemeTable.createdAt),
    )
}

class JooqEntityIdentifierRepository(
    private val context: JooqPersistenceContext,
) : EntityIdentifierRepository {
    override fun findById(tenantId: TenantId, id: EntityIdentifierId): EntityIdentifier? =
        jooqOperation("find entity identifier by id") {
            selectIdentifiers(tenantId)
                .and(EntityIdentifierTable.id.eq(id.value))
                .fetchOne { it.toDomain() }
        }

    override fun findByEntity(
        tenantId: TenantId,
        entityType: EntityType,
        entityId: UUID,
    ): List<EntityIdentifier> = jooqOperation("find identifiers by entity") {
        selectIdentifiers(tenantId)
            .and(EntityIdentifierTable.entityType.eq(entityType.value))
            .and(EntityIdentifierTable.entityId.eq(entityId))
            .orderBy(EntityIdentifierTable.createdAt, EntityIdentifierTable.id)
            .fetch { it.toDomain() }
    }

    override fun findByValue(
        tenantId: TenantId,
        schemeId: IdentifierSchemeId,
        identifier: IdentifierValue,
    ): EntityIdentifier? = jooqOperation("find entity identifier by value") {
        selectIdentifiers(tenantId)
            .and(EntityIdentifierTable.schemeId.eq(schemeId.value))
            .and(EntityIdentifierTable.identifier.eq(identifier.value))
            .fetchOne { it.toDomain() }
    }

    override fun insert(tenantId: TenantId, entityIdentifier: EntityIdentifier): EntityIdentifier =
        jooqOperation("insert entity identifier") {
            val schemeBelongsToTenant = context.dsl().fetchExists(
                context.dsl().selectOne()
                    .from(IdentifierSchemeTable.table)
                    .where(IdentifierSchemeTable.tenantId.eq(tenantId.value))
                    .and(IdentifierSchemeTable.id.eq(entityIdentifier.schemeId.value))
                    .and(IdentifierSchemeTable.entityType.eq(entityIdentifier.entityType.value))
            )
            require(schemeBelongsToTenant) {
                "identifier scheme does not belong to repository tenant: ${entityIdentifier.schemeId.value}"
            }

            val insert = context.dsl()
                .insertInto(EntityIdentifierTable.table)
                .set(EntityIdentifierTable.id, entityIdentifier.id.value)
                .set(EntityIdentifierTable.entityType, entityIdentifier.entityType.value)
                .set(EntityIdentifierTable.entityId, entityIdentifier.entityId)
                .set(EntityIdentifierTable.schemeId, entityIdentifier.schemeId.value)
                .set(EntityIdentifierTable.identifier, entityIdentifier.identifier.value)
            entityIdentifier.createdAt?.let { insert.set(EntityIdentifierTable.createdAt, it) }
            requireNotNull(insert.returning(EntityIdentifierTable.fields).fetchOne()).toDomain()
        }

    private fun selectIdentifiers(tenantId: TenantId) = context.dsl()
        .select(EntityIdentifierTable.fields)
        .from(EntityIdentifierTable.table)
        .join(IdentifierSchemeTable.table)
        .on(IdentifierSchemeTable.id.eq(EntityIdentifierTable.schemeId))
        .where(IdentifierSchemeTable.tenantId.eq(tenantId.value))

    private fun Record.toDomain(): EntityIdentifier = EntityIdentifier(
        id = EntityIdentifierId.of(requireNotNull(get(EntityIdentifierTable.id))),
        entityType = EntityType.of(requireNotNull(get(EntityIdentifierTable.entityType))),
        entityId = requireNotNull(get(EntityIdentifierTable.entityId)),
        schemeId = IdentifierSchemeId.of(requireNotNull(get(EntityIdentifierTable.schemeId))),
        identifier = IdentifierValue.of(requireNotNull(get(EntityIdentifierTable.identifier))),
        createdAt = get(EntityIdentifierTable.createdAt),
    )
}

private object IdentifierSchemeTable {
    const val NAME = "identifier_scheme"
    val table = jooqTable(NAME)
    val id: Field<UUID> = jooqField(NAME, "id", UUID::class.java)
    val tenantId: Field<UUID> = jooqField(NAME, "tenant_id", UUID::class.java)
    val entityType: Field<String> = jooqField(NAME, "entity_type", String::class.java)
    val schemeName: Field<String> = jooqField(NAME, "scheme_name", String::class.java)
    val formatRegex: Field<String> = jooqField(NAME, "format_regex", String::class.java)
    val maxLength: Field<Int> = jooqField(NAME, "max_length", Int::class.java)
    val isPrimary: Field<Boolean> = jooqField(NAME, "is_primary", Boolean::class.java)
    val createdAt: Field<Instant> = jooqField(NAME, "created_at", Instant::class.java)
    val fields: List<SelectFieldOrAsterisk> =
        listOf(id, tenantId, entityType, schemeName, formatRegex, maxLength, isPrimary, createdAt)
}

private object EntityIdentifierTable {
    const val NAME = "entity_identifier"
    val table = jooqTable(NAME)
    val id: Field<UUID> = jooqField(NAME, "id", UUID::class.java)
    val entityType: Field<String> = jooqField(NAME, "entity_type", String::class.java)
    val entityId: Field<UUID> = jooqField(NAME, "entity_id", UUID::class.java)
    val schemeId: Field<UUID> = jooqField(NAME, "scheme_id", UUID::class.java)
    val identifier: Field<String> = jooqField(NAME, "identifier", String::class.java)
    val createdAt: Field<Instant> = jooqField(NAME, "created_at", Instant::class.java)
    val fields: List<SelectFieldOrAsterisk> = listOf(id, entityType, entityId, schemeId, identifier, createdAt)
}
