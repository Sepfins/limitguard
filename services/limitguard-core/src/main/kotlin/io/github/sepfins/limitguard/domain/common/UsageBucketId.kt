package io.github.sepfins.limitguard.domain.common

import java.util.UUID

@JvmInline
value class UsageBucketId(val value: UUID) {
    companion object {
        fun of(value: UUID): UsageBucketId = UsageBucketId(value)

        fun of(value: String): UsageBucketId = of(UUID.fromString(value))
    }
}
