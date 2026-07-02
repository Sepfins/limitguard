package io.github.sepfins.limitguard.domain.common

import java.util.UUID

@JvmInline
value class LimitChangeRequestId(val value: UUID) {
    companion object {
        fun of(value: UUID): LimitChangeRequestId = LimitChangeRequestId(value)

        fun of(value: String): LimitChangeRequestId = of(UUID.fromString(value))
    }
}
