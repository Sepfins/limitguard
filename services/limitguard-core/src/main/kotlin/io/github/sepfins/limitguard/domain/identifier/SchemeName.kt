package io.github.sepfins.limitguard.domain.identifier

@JvmInline
value class SchemeName private constructor(val value: String) {
    companion object {
        fun of(value: String): SchemeName {
            val normalized = value.trim()
            require(normalized.isNotBlank()) { "SchemeName cannot be blank" }
            require(normalized.length <= 100) { "SchemeName cannot be longer than 100 characters" }
            return SchemeName(normalized)
        }
    }
}
