package io.github.sepfins.limitguard.domain.operation

import io.github.sepfins.limitguard.domain.common.OperationTypeId
import java.time.Instant

data class OperationType(
    val id: OperationTypeId,
    val scope: OperationTypeScope,
    val parentId: OperationTypeId?,
    val code: OperationTypeCode,
    val name: OperationTypeName,
    val isLeaf: Boolean,
    val createdAt: Instant? = null
) {
    init {
        require(parentId == null || parentId != id) {
            "parentId must not point to the same operation type"
        }
    }
}
