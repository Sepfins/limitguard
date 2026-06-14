package io.github.sepfins.limitguard.domain.common

import java.util.UUID

@JvmInline
value class MandateId(val id: UUID) {
    companion object {
        fun of(id: String): MandateId = MandateId(UUID.fromString(id))
    }
}
