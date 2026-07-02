package io.github.sepfins.limitguard.domain.common

import java.util.UUID

@JvmInline
value class MandateId(val value: UUID) {
    companion object {
        fun of(value: UUID): MandateId = MandateId(value)

        fun of(value: String): MandateId = of(UUID.fromString(value))
    }
}
