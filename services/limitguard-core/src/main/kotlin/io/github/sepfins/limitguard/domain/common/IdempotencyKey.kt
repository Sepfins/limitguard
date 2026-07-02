package io.github.sepfins.limitguard.domain.common

@JvmInline
value class IdempotencyKey private constructor(val value: String) {
    companion object {
        fun of(value: String): IdempotencyKey {
            val normalized = value.trim()
            require(normalized.isNotBlank()) { "IdempotencyKey cannot be blank" }
            require(normalized.length <= 255) { "IdempotencyKey cannot be longer than 255 characters" }
            return IdempotencyKey(normalized)
        }
    }
}
