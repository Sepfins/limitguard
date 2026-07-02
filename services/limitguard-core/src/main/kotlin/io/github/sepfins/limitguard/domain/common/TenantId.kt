package io.github.sepfins.limitguard.domain.common

import java.util.UUID

@JvmInline
value class TenantId(val value: UUID) {
    companion object {
        fun of(value: UUID): TenantId = TenantId(value)

        fun of(value: String): TenantId = of(UUID.fromString(value))
    }
}
