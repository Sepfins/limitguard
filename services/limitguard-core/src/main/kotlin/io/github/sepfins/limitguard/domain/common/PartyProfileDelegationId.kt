package io.github.sepfins.limitguard.domain.common

import java.util.UUID

@JvmInline
value class PartyProfileDelegationId(val value: UUID) {
    companion object {
        fun of(value: UUID): PartyProfileDelegationId = PartyProfileDelegationId(value)

        fun of(value: String): PartyProfileDelegationId = of(UUID.fromString(value))
    }
}
