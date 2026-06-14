package io.github.sepfins.limitguard.domain.party

@JvmInline
value class LegalForm private constructor(val value: String) {
    companion object {
        fun of(value: String): LegalForm {
            val normalized = value.trim()
            require(normalized.isNotBlank()) { "LegalForm cannot be blank" }
            require(normalized.length <= 100) { "LegalForm cannot be longer than 100 characters" }
            return LegalForm(normalized)
        }
    }
}
