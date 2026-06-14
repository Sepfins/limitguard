package io.github.sepfins.limitguard.domain.common

import java.util.UUID

@JvmInline
value class IdentifierSchemeId(val id: UUID) {
    companion object {
        fun of(id: String): IdentifierSchemeId = IdentifierSchemeId(UUID.fromString(id))
    }
}
