package io.github.sepfins.limitguard.domain.common

@JvmInline
value class ExternalReference private constructor(val value: String) {
    companion object {
        fun of(value: String): ExternalReference {
            val normalized = value.trim()
            require(normalized.isNotBlank()) { "ExternalReference cannot be blank" }
            require(normalized.length <= 255) { "ExternalReference cannot exceed 255 characters" }
            return ExternalReference(normalized)
        }
    }
}
