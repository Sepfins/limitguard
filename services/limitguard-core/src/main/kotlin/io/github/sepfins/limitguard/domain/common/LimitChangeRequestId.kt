package io.github.sepfins.limitguard.domain.common

import java.util.UUID

@JvmInline
value class LimitChangeRequestId(val id: UUID) {
    companion object {
        fun of(id: String): LimitChangeRequestId = LimitChangeRequestId(UUID.fromString(id))
    }
}
