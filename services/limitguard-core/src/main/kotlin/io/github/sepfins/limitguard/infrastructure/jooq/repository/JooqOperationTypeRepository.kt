package io.github.sepfins.limitguard.infrastructure.jooq.repository

import io.github.sepfins.limitguard.domain.common.OperationTypeId
import io.github.sepfins.limitguard.domain.common.TenantId
import io.github.sepfins.limitguard.domain.operation.OperationType
import io.github.sepfins.limitguard.domain.operation.OperationTypeCode
import io.github.sepfins.limitguard.domain.operation.OperationTypeName
import io.github.sepfins.limitguard.domain.operation.OperationTypeScope
import io.github.sepfins.limitguard.infrastructure.jooq.JooqPersistenceContext
import io.github.sepfins.limitguard.infrastructure.jooq.jooqField
import io.github.sepfins.limitguard.infrastructure.jooq.jooqOperation
import io.github.sepfins.limitguard.infrastructure.jooq.jooqTable
import io.github.sepfins.limitguard.port.persistence.OperationTypeRepository
import org.jooq.Condition
import org.jooq.Field
import org.jooq.Record
import org.jooq.SelectFieldOrAsterisk
import java.time.Instant
import java.util.UUID

class JooqOperationTypeRepository(
    private val context: JooqPersistenceContext,
) : OperationTypeRepository {
    override fun findVisibleToTenant(tenantId: TenantId): List<OperationType> =
        jooqOperation("find operation types visible to tenant") {
            selectOperationTypes()
                .where(visibleTo(tenantId))
                .orderBy(OperationTypeTable.code, OperationTypeTable.id)
                .fetch { it.toDomain() }
        }

    override fun findById(tenantId: TenantId, id: OperationTypeId): OperationType? =
        jooqOperation("find operation type by id") {
            selectOperationTypes()
                .where(visibleTo(tenantId))
                .and(OperationTypeTable.id.eq(id.value))
                .fetchOne { it.toDomain() }
        }

    override fun findByCodeVisibleToTenant(
        tenantId: TenantId,
        code: OperationTypeCode,
    ): List<OperationType> = jooqOperation("find operation types by visible code") {
        selectOperationTypes()
            .where(visibleTo(tenantId))
            .and(OperationTypeTable.code.eq(code.value))
            .orderBy(OperationTypeTable.isGlobal.desc(), OperationTypeTable.id)
            .fetch { it.toDomain() }
    }

    override fun findByParentId(
        tenantId: TenantId,
        parentId: OperationTypeId,
    ): List<OperationType> = jooqOperation("find operation types by parent") {
        selectOperationTypes()
            .where(visibleTo(tenantId))
            .and(OperationTypeTable.parentId.eq(parentId.value))
            .orderBy(OperationTypeTable.code, OperationTypeTable.id)
            .fetch { it.toDomain() }
    }

    override fun insert(tenantId: TenantId, operationType: OperationType): OperationType =
        jooqOperation("insert operation type") {
            requireScopeMatchesTenant(tenantId, operationType.scope)
            val insert = context.dsl()
                .insertInto(OperationTypeTable.table)
                .set(OperationTypeTable.id, operationType.id.value)
                .set(OperationTypeTable.tenantId, operationType.scope.tenantId()?.value)
                .set(OperationTypeTable.parentId, operationType.parentId?.value)
                .set(OperationTypeTable.code, operationType.code.value)
                .set(OperationTypeTable.name, operationType.name.value)
                .set(OperationTypeTable.isLeaf, operationType.isLeaf)
                .set(OperationTypeTable.isGlobal, operationType.scope == OperationTypeScope.Global)
            operationType.createdAt?.let { insert.set(OperationTypeTable.createdAt, it) }
            requireNotNull(insert.returning(OperationTypeTable.fields).fetchOne()).toDomain()
        }

    override fun update(tenantId: TenantId, operationType: OperationType): OperationType =
        jooqOperation("update operation type") {
            requireScopeMatchesTenant(tenantId, operationType.scope)
            requireNotNull(
                context.dsl()
                    .update(OperationTypeTable.table)
                    .set(OperationTypeTable.tenantId, operationType.scope.tenantId()?.value)
                    .set(OperationTypeTable.parentId, operationType.parentId?.value)
                    .set(OperationTypeTable.code, operationType.code.value)
                    .set(OperationTypeTable.name, operationType.name.value)
                    .set(OperationTypeTable.isLeaf, operationType.isLeaf)
                    .set(OperationTypeTable.isGlobal, operationType.scope == OperationTypeScope.Global)
                    .where(OperationTypeTable.id.eq(operationType.id.value))
                    .and(visibleTo(tenantId))
                    .returning(OperationTypeTable.fields)
                    .fetchOne()
            ) { "operation type not found: ${operationType.id.value}" }.toDomain()
        }

    private fun visibleTo(tenantId: TenantId): Condition =
        OperationTypeTable.tenantId.isNull.or(OperationTypeTable.tenantId.eq(tenantId.value))

    private fun selectOperationTypes() = context.dsl()
        .select(OperationTypeTable.fields)
        .from(OperationTypeTable.table)

    private fun Record.toDomain(): OperationType {
        val isGlobal = requireNotNull(get(OperationTypeTable.isGlobal))
        val scopedTenantId = get(OperationTypeTable.tenantId)
        val scope = if (isGlobal) {
            require(scopedTenantId == null) { "global operation type must not carry a tenant" }
            OperationTypeScope.Global
        } else {
            OperationTypeScope.TenantSpecific(TenantId.of(requireNotNull(scopedTenantId)))
        }
        return OperationType(
            id = OperationTypeId.of(requireNotNull(get(OperationTypeTable.id))),
            scope = scope,
            parentId = get(OperationTypeTable.parentId)?.let(OperationTypeId::of),
            code = OperationTypeCode.of(requireNotNull(get(OperationTypeTable.code))),
            name = OperationTypeName.of(requireNotNull(get(OperationTypeTable.name))),
            isLeaf = requireNotNull(get(OperationTypeTable.isLeaf)),
            createdAt = get(OperationTypeTable.createdAt),
        )
    }

    private fun OperationTypeScope.tenantId(): TenantId? =
        (this as? OperationTypeScope.TenantSpecific)?.tenantId

    private fun requireScopeMatchesTenant(tenantId: TenantId, scope: OperationTypeScope) {
        if (scope is OperationTypeScope.TenantSpecific) {
            require(scope.tenantId == tenantId) { "operation type scope does not match repository tenant" }
        }
    }
}

private object OperationTypeTable {
    const val NAME = "operation_type"
    val table = jooqTable(NAME)
    val id: Field<UUID> = jooqField(NAME, "id", UUID::class.java)
    val tenantId: Field<UUID> = jooqField(NAME, "tenant_id", UUID::class.java)
    val parentId: Field<UUID> = jooqField(NAME, "parent_id", UUID::class.java)
    val code: Field<String> = jooqField(NAME, "code", String::class.java)
    val name: Field<String> = jooqField(NAME, "name", String::class.java)
    val isLeaf: Field<Boolean> = jooqField(NAME, "is_leaf", Boolean::class.java)
    val isGlobal: Field<Boolean> = jooqField(NAME, "is_global", Boolean::class.java)
    val createdAt: Field<Instant> = jooqField(NAME, "created_at", Instant::class.java)
    val fields: List<SelectFieldOrAsterisk> =
        listOf(id, tenantId, parentId, code, name, isLeaf, isGlobal, createdAt)
}
