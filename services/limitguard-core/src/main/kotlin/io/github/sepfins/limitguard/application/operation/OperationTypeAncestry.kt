package io.github.sepfins.limitguard.application.operation

import io.github.sepfins.limitguard.api.errors.ConflictException
import io.github.sepfins.limitguard.api.errors.NotFoundException
import io.github.sepfins.limitguard.domain.common.OperationTypeId
import io.github.sepfins.limitguard.domain.common.TenantId
import io.github.sepfins.limitguard.domain.operation.OperationType
import io.github.sepfins.limitguard.domain.operation.OperationTypeScope
import io.github.sepfins.limitguard.port.persistence.OperationTypeRepository

internal class OperationTypeAncestry(private val repository: OperationTypeRepository) {
    fun resolve(tenantId: TenantId, selectedId: OperationTypeId): List<OperationType> {
        val ancestry = repository.findAncestry(tenantId, selectedId).associateBy { it.operationType.id }
        val path = mutableListOf<OperationType>()
        val visited = hashSetOf<OperationTypeId>()
        var currentId: OperationTypeId? = selectedId

        while (currentId != null) {
            if (!visited.add(currentId)) {
                throw ConflictException("Operation type hierarchy contains a cycle at ${currentId.value}")
            }
            val node = ancestry[currentId]
                ?: throw NotFoundException("Operation type not found: ${tenantId.value} / ${currentId.value}")
            val operation = node.operationType
            if (path.lastOrNull()?.scope == OperationTypeScope.Global && operation.scope != OperationTypeScope.Global) {
                throw ConflictException("Global operation types may have only global ancestors")
            }
            if (operation.isLeaf && (path.isNotEmpty() || node.hasChildren)) {
                throw ConflictException("Declared operation leaf has children: ${operation.id.value}")
            }
            path += operation
            currentId = operation.parentId
        }
        return path
    }
}
