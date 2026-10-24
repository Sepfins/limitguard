package io.github.sepfins.limitguard.port.persistence

import io.github.sepfins.limitguard.domain.common.OperationTypeId
import io.github.sepfins.limitguard.domain.common.TenantId
import io.github.sepfins.limitguard.domain.operation.OperationType
import io.github.sepfins.limitguard.domain.operation.OperationTypeCode

interface OperationTypeRepository {
    /**
     * Serializes operation growth and configuration admission across the shared taxonomy
     * through transaction completion. Acquire before tenant envelope/catalog row locks,
     * then read fresh ancestry and configurations, including all tenants for global growth.
     */
    fun lockCatalog()

    fun findVisibleToTenant(tenantId: TenantId): List<OperationType>
    fun findById(tenantId: TenantId, id: OperationTypeId): OperationType?
    fun findByCodeVisibleToTenant(tenantId: TenantId, code: OperationTypeCode): List<OperationType>
    fun findByParentId(tenantId: TenantId, parentId: OperationTypeId): List<OperationType>

    /** Pages the visible set in tree order: each parent before its children, siblings by code. */
    fun findPage(tenantId: TenantId, search: String?, leaf: Boolean?, offset: Int, limit: Int): List<OperationType>
    fun count(tenantId: TenantId, search: String?, leaf: Boolean?): Long

    /**
     * Checks a visible parent's structural leaf status without exposing child identities.
     * Shared-taxonomy adapters must include private children of global parents too.
     */
    fun hasChildren(tenantId: TenantId, parentId: OperationTypeId): Boolean =
        findByParentId(tenantId, parentId).isNotEmpty()

    /**
     * Reads visible ancestry and structural child flags from one consistent snapshot.
     * Stops at missing, invisible, or repeated nodes; the application validates the path.
     * Concurrent adapters must override this fallback or provide snapshot isolation around it.
     */
    fun findAncestry(tenantId: TenantId, selectedId: OperationTypeId): List<OperationTypeAncestryNode> {
        val nodes = mutableListOf<OperationTypeAncestryNode>()
        val visited = hashSetOf<OperationTypeId>()
        var currentId: OperationTypeId? = selectedId
        while (currentId != null && visited.add(currentId)) {
            val operation = findById(tenantId, currentId) ?: break
            nodes += OperationTypeAncestryNode(operation, hasChildren(tenantId, operation.id))
            currentId = operation.parentId
        }
        return nodes
    }

    fun insert(tenantId: TenantId, operationType: OperationType): OperationType
    fun update(tenantId: TenantId, operationType: OperationType): OperationType
}

data class OperationTypeAncestryNode(val operationType: OperationType, val hasChildren: Boolean)
