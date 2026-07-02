package io.github.sepfins.limitguard.domain.common

import java.util.UUID

@JvmInline
value class EntityIdentifierId(val value: UUID) {
    companion object {
        fun of(value: UUID): EntityIdentifierId = EntityIdentifierId(value)

        fun of(value: String): EntityIdentifierId = of(UUID.fromString(value))
    }
}
