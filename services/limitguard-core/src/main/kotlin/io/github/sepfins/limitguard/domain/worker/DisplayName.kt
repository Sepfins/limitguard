package io.github.sepfins.limitguard.domain.worker

@JvmInline
value class DisplayName private constructor(val value: String) {
    companion object {
        fun of(value: String): DisplayName {
            val normalized = value.trim()
            require(normalized.isNotBlank()) { "DisplayName cannot be blank" }
            require(normalized.length <= 255) { "DisplayName cannot be longer than 255 characters" }
            return DisplayName(normalized)
        }
    }
}
