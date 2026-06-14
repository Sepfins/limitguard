package io.github.sepfins.limitguard.domain.change

@JvmInline
value class ScopeHash private constructor(val value: String) {
    companion object {
        fun of(value: String): ScopeHash {
            val normalized = value.trim()
            require(normalized.isNotBlank()) { "ScopeHash cannot be blank" }
            require(normalized.length <= 128) { "ScopeHash cannot be longer than 128 characters" }
            return ScopeHash(normalized)
        }
    }
}
