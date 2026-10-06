package io.github.sepfins.limitguard.domain.limit

import io.github.sepfins.limitguard.domain.common.OperationTypeId

sealed interface LimitOperationScope {
    data object AllOperations : LimitOperationScope

    data class OperationTypeSpecific(val operationTypeId: OperationTypeId) : LimitOperationScope

    fun includes(operationTypeId: OperationTypeId): Boolean = includes(listOf(operationTypeId))

    fun includes(operationTypePath: Collection<OperationTypeId>): Boolean =
        when (this) {
            AllOperations -> true
            is OperationTypeSpecific -> operationTypeId in operationTypePath
        }
}
