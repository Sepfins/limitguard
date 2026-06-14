package io.github.sepfins.limitguard.domain.limit

@JvmInline
value class LimitConfigurationName private constructor(val value: String) {
    companion object {
        fun of(value: String): LimitConfigurationName {
            val normalized = value.trim()
            require(normalized.isNotBlank()) { "LimitConfigurationName cannot be blank" }
            require(normalized.length <= 255) { "LimitConfigurationName cannot be longer than 255 characters" }
            return LimitConfigurationName(normalized)
        }
    }
}
