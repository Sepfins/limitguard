package io.github.sepfins.limitguard.domain.party

@JvmInline
value class FirstName private constructor(val value: String) {
    companion object {
        fun of(value: String): FirstName {
            val normalized = value.trim()
            require(normalized.isNotBlank()) { "FirstName cannot be blank" }
            require(normalized.length <= 255) { "FirstName cannot be longer than 255 characters" }
            return FirstName(normalized)
        }
    }
}
