package io.github.sepfins.limitguard.domain.common

import java.util.UUID

@JvmInline
value class LimitChangePolicyId(val id: UUID) {
    companion object {
        fun of(id: String): LimitChangePolicyId = LimitChangePolicyId(UUID.fromString(id))
    }
}
