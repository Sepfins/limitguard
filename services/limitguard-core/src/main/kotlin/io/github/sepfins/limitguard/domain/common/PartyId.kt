package io.github.sepfins.limitguard.domain.common

import java.util.UUID

@JvmInline
value class PartyId(val id: UUID) {
    companion object {
        fun of(id: String): PartyId = PartyId(UUID.fromString(id))
    }
}
