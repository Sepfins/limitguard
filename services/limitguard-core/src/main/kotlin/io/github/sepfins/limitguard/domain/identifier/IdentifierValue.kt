package io.github.sepfins.limitguard.domain.identifier

@JvmInline
value class IdentifierValue private constructor(val value: String) {
    companion object {
        fun of(value: String): IdentifierValue {
            val normalized = value.trim()
            require(normalized.isNotBlank()) { "IdentifierValue cannot be blank" }
            require(normalized.length <= 500) { "IdentifierValue cannot be longer than 500 characters" }
            return IdentifierValue(normalized)
        }
    }
}
