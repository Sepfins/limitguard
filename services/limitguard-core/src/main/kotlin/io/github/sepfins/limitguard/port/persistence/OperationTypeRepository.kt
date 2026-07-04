package io.github.sepfins.limitguard.port.persistence

import io.github.sepfins.limitguard.domain.common.OperationTypeId
import io.github.sepfins.limitguard.domain.common.TenantId
import io.github.sepfins.limitguard.domain.operation.OperationType
import io.github.sepfins.limitguard.domain.operation.OperationTypeCode
import io.github.sepfins.limitguard.domain.operation.OperationTypeScope

interface OperationTypeRepository {
    fun findVisibleToTenant(tenantId: TenantId): List<OperationType>
    fun findById(tenantId: TenantId, id: OperationTypeId): OperationType?
    fun findByCode(tenantId: TenantId, code: OperationTypeCode): List<OperationType>
    fun findByParentId(tenantId: TenantId, parentId: OperationTypeId): List<OperationType>
    fun insert(tenantId: TenantId, operationType: OperationType): OperationType
    fun update(tenantId: TenantId, operationType: OperationType): OperationType
}
