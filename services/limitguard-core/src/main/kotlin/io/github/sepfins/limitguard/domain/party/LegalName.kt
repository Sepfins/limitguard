package io.github.sepfins.limitguard.domain.party

@JvmInline
value class LegalName private constructor(val value: String) {
    companion object {
        fun of(value: String): LegalName {
            val normalized = value.trim()
            require(normalized.isNotBlank()) { "LegalName cannot be blank" }
            require(normalized.length <= 255) { "LegalName cannot be longer than 255 characters" }
            return LegalName(normalized)
        }
    }
}
