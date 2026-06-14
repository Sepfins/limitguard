package io.github.sepfins.limitguard.domain.limit

@JvmInline
value class LimitConfigurationCode private constructor(val value: String) {
    companion object {
        fun of(value: String): LimitConfigurationCode {
            val normalized = value.trim().uppercase()
            require(normalized.isNotBlank()) { "LimitConfigurationCode cannot be blank" }
            require(normalized.length <= 100) { "LimitConfigurationCode cannot be longer than 100 characters" }
            return LimitConfigurationCode(normalized)
        }
    }
}
