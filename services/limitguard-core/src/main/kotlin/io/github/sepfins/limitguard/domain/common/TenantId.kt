package io.github.sepfins.limitguard.domain.common

import java.util.UUID

@JvmInline
value class TenantId (val id: UUID) {
    companion object {
        fun of(id: UUID): TenantId = TenantId(id)
    }
}