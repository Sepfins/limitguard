package io.github.sepfins.limitguard.domain.common

import java.util.UUID

@JvmInline
value class LimitConfigurationId(val value: UUID) {
    companion object {
        fun of(value: UUID): LimitConfigurationId = LimitConfigurationId(value)

        fun of(value: String): LimitConfigurationId = of(UUID.fromString(value))
    }
}
