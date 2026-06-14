package io.github.sepfins.limitguard.domain.common

import java.util.UUID

@JvmInline
value class IdempotencyKey private constructor(val key: String) {
    companion object {
        fun of(key: String) : IdempotencyKey {
            val normalized = key.trim()
            require(normalized.isNotBlank()) { "IdempotencyKey cannot be blank" }
            require(normalized.length <= 255) { "IdempotencyKey cannot be longer than 255 characters" }
            return IdempotencyKey(normalized)
        }
    }
}
