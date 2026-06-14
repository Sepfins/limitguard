package io.github.sepfins.limitguard.domain.operation

import io.github.sepfins.limitguard.domain.common.OperationTypeId

data class OperationTypeNode(
    val id: OperationTypeId,
    val scope: OperationTypeScope,
    val code: OperationTypeCode,
    val name: OperationTypeName,
    val isLeaf: Boolean,
    val children: List<OperationTypeNode> = emptyList()
)
