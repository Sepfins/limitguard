package io.github.sepfins.limitguard.domain.common

import java.util.UUID

@JvmInline
value class PartyProfileDelegationId(val id: UUID) {
    companion object {
        fun of(id: String) : PartyProfileDelegationId = PartyProfileDelegationId(UUID.fromString(id))
    }
}
