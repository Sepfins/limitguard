package io.github.sepfins.limitguard.domain.party

@JvmInline
value class LastName private constructor(val value: String) {
    companion object {
        fun of(value: String): LastName {
            val normalized = value.trim()
            require(normalized.isNotBlank()) { "LastName cannot be blank" }
            require(normalized.length <= 255) { "LastName cannot be longer than 255 characters" }
            return LastName(normalized)
        }
    }
}
