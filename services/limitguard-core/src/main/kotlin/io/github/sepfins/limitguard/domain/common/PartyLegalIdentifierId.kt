package io.github.sepfins.limitguard.domain.common

import java.util.UUID

@JvmInline
value class PartyLegalIdentifierId(val value: UUID) {
    companion object {
        fun of(value: UUID): PartyLegalIdentifierId = PartyLegalIdentifierId(value)

        fun of(value: String): PartyLegalIdentifierId = of(UUID.fromString(value))
    }
}
