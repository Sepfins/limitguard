package io.github.sepfins.limitguard.domain.mandate

@JvmInline
value class MandateName private constructor(val value: String) {
    companion object {
        fun of(value: String): MandateName {
            val normalized = value.trim()
            require(normalized.isNotBlank()) { "MandateName cannot be blank" }
            require(normalized.length <= 255) { "MandateName cannot be longer than 255 characters" }
            return MandateName(normalized)
        }
    }
}
