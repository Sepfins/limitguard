package io.github.sepfins.limitguard.domain.limit

import io.github.sepfins.limitguard.domain.common.OperationTypeId

sealed interface LimitOperationScope {
    data object Global : LimitOperationScope

    data class OperationTypeSpecific(val operationTypeId: OperationTypeId) : LimitOperationScope
}