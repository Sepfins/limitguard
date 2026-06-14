package io.github.sepfins.limitguard.domain.change

@JvmInline
value class LimitChangePolicyName private constructor(val value: String) {
    companion object {
        fun of(value: String): LimitChangePolicyName {
            val normalized = value.trim()
            require(normalized.isNotBlank()) { "LimitChangePolicyName cannot be blank" }
            require(normalized.length <= 255) { "LimitChangePolicyName cannot be longer than 255 characters" }
            return LimitChangePolicyName(normalized)
        }
    }
}
