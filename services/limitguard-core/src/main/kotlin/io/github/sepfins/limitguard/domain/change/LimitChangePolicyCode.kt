package io.github.sepfins.limitguard.domain.change

@JvmInline
value class LimitChangePolicyCode private constructor(val value: String) {
    companion object {
        fun of(value: String): LimitChangePolicyCode {
            val normalized = value.trim().uppercase()
            require(normalized.isNotBlank()) { "LimitChangePolicyCode cannot be blank" }
            require(normalized.length <= 100) { "LimitChangePolicyCode cannot be longer than 100 characters" }
            return LimitChangePolicyCode(normalized)
        }
    }
}
