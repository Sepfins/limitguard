package io.github.sepfins.limitguard.infrastructure.jooq.repository

import io.github.sepfins.limitguard.api.errors.ConflictException
import io.github.sepfins.limitguard.domain.common.OperationTypeId
import io.github.sepfins.limitguard.domain.common.TenantId
import io.github.sepfins.limitguard.domain.operation.OperationType
import io.github.sepfins.limitguard.domain.operation.OperationTypeCode
import io.github.sepfins.limitguard.domain.operation.OperationTypeName
import io.github.sepfins.limitguard.domain.operation.OperationTypeScope
import io.github.sepfins.limitguard.infrastructure.jooq.JooqPersistenceContext
import io.github.sepfins.limitguard.infrastructure.jooq.jooqOperation
import io.github.sepfins.limitguard.infrastructure.jooq.generated.tables.OperationType as GeneratedOperationTypeTable
import io.github.sepfins.limitguard.port.persistence.OperationTypeAncestryNode
import io.github.sepfins.limitguard.port.persistence.OperationTypeRepository
import org.jooq.Condition
import org.jooq.Record
import org.jooq.SelectFieldOrAsterisk
import org.jooq.impl.DSL
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
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

    override fun hasChildren(tenantId: TenantId, parentId: OperationTypeId): Boolean =
        jooqOperation("check operation type children") {
            val parent = OperationTypeTable.table.`as`("parent")
            context.dsl().fetchExists(
                context.dsl().selectOne()
                    .from(OperationTypeTable.table)
                    .join(parent).on(OperationTypeTable.parentId.eq(parent.ID))
                    .where(parent.ID.eq(parentId.value))
                    .and(parent.TENANT_ID.isNull.or(parent.TENANT_ID.eq(tenantId.value))),
            )
        }

    override fun findAncestry(tenantId: TenantId, selectedId: OperationTypeId): List<OperationTypeAncestryNode> =
        jooqOperation("read operation type ancestry snapshot") {
            val dsl = context.dsl()
            val ancestor = OperationTypeTable.table.`as`("ancestor")
            val ancestryTable = DSL.table(DSL.name("operation_ancestry"))
            val ancestryParent = DSL.field(DSL.name("operation_ancestry", "parent_id"), UUID::class.java)
            // UNION terminates corrupt cycles without requiring a valid hierarchy to read it.
            val ancestry = DSL.name("operation_ancestry").fields("id", "parent_id").`as`(
                dsl.select(OperationTypeTable.id, OperationTypeTable.parentId)
                    .from(OperationTypeTable.table)
                    .where(visibleTo(tenantId)).and(OperationTypeTable.id.eq(selectedId.value))
                    .union(
                        dsl.select(ancestor.ID, ancestor.PARENT_ID).from(ancestor)
                            .join(ancestryTable).on(ancestor.ID.eq(ancestryParent))
                            .where(visibleTo(tenantId, ancestor)),
                    ),
            )
            val child = OperationTypeTable.table.`as`("child")
            val hasChildren = DSL.exists(
                dsl.selectOne().from(child).where(child.PARENT_ID.eq(OperationTypeTable.id)),
            ).`as`("has_children")
            dsl.withRecursive(ancestry)
                .select(OperationTypeTable.fields + hasChildren)
                .from(OperationTypeTable.table)
                .where(OperationTypeTable.id.`in`(dsl.select(ancestry.field("id", UUID::class.java)).from(ancestry)))
                .fetch { OperationTypeAncestryNode(it.toDomain(), requireNotNull(it.get(hasChildren))) }
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
            operationType.createdAt?.let { insert.set(OperationTypeTable.createdAt, it.toOffsetDateTime()) }
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

    private fun visibleTo(
        tenantId: TenantId,
        table: GeneratedOperationTypeTable = OperationTypeTable.table,
    ): Condition = table.TENANT_ID.isNull.or(table.TENANT_ID.eq(tenantId.value))

    private fun selectOperationTypes() = context.dsl()
        .select(OperationTypeTable.fields)
        .from(OperationTypeTable.table)

    private fun Record.toDomain(): OperationType {
        val id = OperationTypeId.of(requireNotNull(get(OperationTypeTable.id)))
        val parentId = get(OperationTypeTable.parentId)?.let(OperationTypeId::of)
        if (parentId == id) {
            throw ConflictException("Operation type hierarchy contains a cycle at ${id.value}")
        }
        val isGlobal = requireNotNull(get(OperationTypeTable.isGlobal))
        val scopedTenantId = get(OperationTypeTable.tenantId)
        val scope = if (isGlobal) {
            require(scopedTenantId == null) { "global operation type must not carry a tenant" }
            OperationTypeScope.Global
        } else {
            OperationTypeScope.TenantSpecific(TenantId.of(requireNotNull(scopedTenantId)))
        }
        return OperationType(
            id = id,
            scope = scope,
            parentId = parentId,
            code = OperationTypeCode.of(requireNotNull(get(OperationTypeTable.code))),
            name = OperationTypeName.of(requireNotNull(get(OperationTypeTable.name))),
            isLeaf = requireNotNull(get(OperationTypeTable.isLeaf)),
            createdAt = get(OperationTypeTable.createdAt)?.toInstant(),
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
    val table = GeneratedOperationTypeTable.OPERATION_TYPE
    val id = table.ID
    val tenantId = table.TENANT_ID
    val parentId = table.PARENT_ID
    val code = table.CODE
    val name = table.NAME
    val isLeaf = table.IS_LEAF
    val isGlobal = table.IS_GLOBAL
    val createdAt = table.CREATED_AT
    val fields: List<SelectFieldOrAsterisk> = table.fields().toList()
}

private fun Instant.toOffsetDateTime(): OffsetDateTime = OffsetDateTime.ofInstant(this, ZoneOffset.UTC)
