package io.github.sepfins.limitguard.domain.common

import java.util.UUID

@JvmInline
value class OperationTypeId(val value: UUID) {
    companion object {
        fun of(value: UUID): OperationTypeId = OperationTypeId(value)

        fun of(value: String): OperationTypeId = of(UUID.fromString(value))
    }
}
