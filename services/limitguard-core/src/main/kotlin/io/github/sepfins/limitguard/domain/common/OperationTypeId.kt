package io.github.sepfins.limitguard.domain.common

import java.util.UUID

@JvmInline
value class OperationTypeId(val id: UUID) {
    companion object {
        fun of(id: String): OperationTypeId = OperationTypeId(UUID.fromString(id))
    }
}