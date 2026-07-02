package io.github.sepfins.limitguard.domain.common

import java.util.UUID

@JvmInline
value class PartyProfileId(val value: UUID) {
    companion object {
        fun of(value: UUID): PartyProfileId = PartyProfileId(value)

        fun of(value: String): PartyProfileId = of(UUID.fromString(value))
    }
}
