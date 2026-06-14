package io.github.sepfins.limitguard.domain.common

import java.util.UUID

@JvmInline
value class PartyProfileId (val partyProfileId: UUID) {
    companion object {
        fun of(partyProfileId: String): PartyProfileId = PartyProfileId(UUID.fromString(partyProfileId))
    }
}