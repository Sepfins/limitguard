package io.github.sepfins.limitguard.domain.limit

@JvmInline
value class LimitRuleName private constructor(val value: String) {
    companion object {
        fun of(value: String): LimitRuleName {
            val normalized = value.trim()
            require(normalized.isNotBlank()) { "LimitRuleName cannot be blank" }
            require(normalized.length <= 255) { "LimitRuleName cannot be longer than 255 characters" }
            return LimitRuleName(normalized)
        }
    }
}
