package io.github.sepfins.limitguard.domain.common

import java.util.UUID

@JvmInline
value class IdentifierSchemeId(val value: UUID) {
    companion object {
        fun of(value: UUID): IdentifierSchemeId = IdentifierSchemeId(value)

        fun of(value: String): IdentifierSchemeId = of(UUID.fromString(value))
    }
}
