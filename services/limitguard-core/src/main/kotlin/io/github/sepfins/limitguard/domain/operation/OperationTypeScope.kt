package io.github.sepfins.limitguard.domain.operation

import io.github.sepfins.limitguard.domain.common.TenantId

sealed interface OperationTypeScope {
    data object Global : OperationTypeScope

    data class TenantSpecific(val tenantId: TenantId) : OperationTypeScope
}