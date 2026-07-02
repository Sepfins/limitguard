package io.github.sepfins.limitguard.domain.common

import java.util.UUID

@JvmInline
value class PartyId(val value: UUID) {
    companion object {
        fun of(value: UUID): PartyId = PartyId(value)

        fun of(value: String): PartyId = of(UUID.fromString(value))
    }
}
