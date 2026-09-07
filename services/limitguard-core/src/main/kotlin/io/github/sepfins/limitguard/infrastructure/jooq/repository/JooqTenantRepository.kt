package io.github.sepfins.limitguard.infrastructure.jooq.repository

import io.github.sepfins.limitguard.domain.common.TenantId
import io.github.sepfins.limitguard.domain.tenant.NoMatchingRuleBehavior
import io.github.sepfins.limitguard.domain.tenant.Tenant
import io.github.sepfins.limitguard.domain.tenant.TenantName
import io.github.sepfins.limitguard.infrastructure.jooq.JooqPersistenceContext
import io.github.sepfins.limitguard.infrastructure.jooq.jooqField
import io.github.sepfins.limitguard.infrastructure.jooq.jooqOperation
import io.github.sepfins.limitguard.infrastructure.jooq.jooqTable
import io.github.sepfins.limitguard.infrastructure.jooq.postgresEnum
import io.github.sepfins.limitguard.infrastructure.jooq.toJsonb
import io.github.sepfins.limitguard.infrastructure.jooq.toMetadata
import io.github.sepfins.limitguard.port.persistence.TenantRepository
import org.jooq.Field
import org.jooq.JSONB
import org.jooq.Record
import org.jooq.SelectFieldOrAsterisk
import java.time.Instant
import java.util.UUID

class JooqTenantRepository(
    private val context: JooqPersistenceContext,
) : TenantRepository {
    override fun exists(tenantId: TenantId): Boolean = jooqOperation("check tenant existence") {
        context.dsl().fetchExists(
            context.dsl().selectOne()
                .from(TenantTable.table)
                .where(TenantTable.id.eq(tenantId.value))
        )
    }

    override fun findById(tenantId: TenantId): Tenant? = jooqOperation("find tenant by id") {
        selectTenants().where(TenantTable.id.eq(tenantId.value)).fetchOne { it.toDomain() }
    }

    override fun findByName(name: TenantName): Tenant? = jooqOperation("find tenant by name") {
        selectTenants().where(TenantTable.name.eq(name.value)).fetchOne { it.toDomain() }
    }

    override fun insert(tenant: Tenant): Tenant = jooqOperation("insert tenant") {
        val insert = context.dsl()
            .insertInto(TenantTable.table)
            .set(TenantTable.id, tenant.id.value)
            .set(TenantTable.name, tenant.name.value)
            .set(TenantTable.config, tenant.config.toJsonb())
            .set(
                TenantTable.noMatchingRuleBehavior,
                postgresEnum(tenant.noMatchingRuleBehavior.name, "no_matching_rule_behavior"),
            )
        tenant.createdAt?.let { insert.set(TenantTable.createdAt, it) }
        requireNotNull(insert.returning(TenantTable.fields).fetchOne()).toDomain()
    }

    override fun update(tenant: Tenant): Tenant = jooqOperation("update tenant") {
        requireNotNull(
            context.dsl()
                .update(TenantTable.table)
                .set(TenantTable.name, tenant.name.value)
                .set(TenantTable.config, tenant.config.toJsonb())
                .set(
                    TenantTable.noMatchingRuleBehavior,
                    postgresEnum(tenant.noMatchingRuleBehavior.name, "no_matching_rule_behavior"),
                )
                .where(TenantTable.id.eq(tenant.id.value))
                .returning(TenantTable.fields)
                .fetchOne()
        ) { "tenant not found: ${tenant.id.value}" }.toDomain()
    }

    private fun selectTenants() = context.dsl().select(TenantTable.fields).from(TenantTable.table)

    private fun Record.toDomain(): Tenant = Tenant(
        id = TenantId.of(requireNotNull(get(TenantTable.id))),
        name = TenantName.of(requireNotNull(get(TenantTable.name))),
        config = get(TenantTable.config).toMetadata(),
        noMatchingRuleBehavior = NoMatchingRuleBehavior.valueOf(
            requireNotNull(get(TenantTable.noMatchingRuleBehavior))
        ),
        createdAt = get(TenantTable.createdAt),
    )
}

private object TenantTable {
    const val NAME = "tenant"
    val table = jooqTable(NAME)
    val id: Field<UUID> = jooqField(NAME, "id", UUID::class.java)
    val name: Field<String> = jooqField(NAME, "name", String::class.java)
    val config: Field<JSONB> = jooqField(NAME, "config", JSONB::class.java)
    val noMatchingRuleBehavior: Field<String> =
        jooqField(NAME, "no_matching_rule_behavior", String::class.java)
    val createdAt: Field<Instant> = jooqField(NAME, "created_at", Instant::class.java)
    val fields: List<SelectFieldOrAsterisk> = listOf(id, name, config, noMatchingRuleBehavior, createdAt)
}
