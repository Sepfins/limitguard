package io.github.sepfins.limitguard.domain.common

import java.util.UUID

@JvmInline
value class LimitChangePolicyId(val value: UUID) {
    companion object {
        fun of(value: UUID): LimitChangePolicyId = LimitChangePolicyId(value)

        fun of(value: String): LimitChangePolicyId = of(UUID.fromString(value))
    }
}
