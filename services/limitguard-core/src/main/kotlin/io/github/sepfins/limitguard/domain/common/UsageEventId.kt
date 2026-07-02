package io.github.sepfins.limitguard.domain.common

import java.util.UUID

@JvmInline
value class UsageEventId(val value: UUID) {
    companion object {
        fun of(value: UUID): UsageEventId = UsageEventId(value)

        fun of(value: String): UsageEventId = of(UUID.fromString(value))
    }
}
