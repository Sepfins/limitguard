package io.github.sepfins.limitguard.domain.party

@JvmInline
value class LegalIdentifierValue private constructor(val value: String) {
    companion object {
        fun of(value: String): LegalIdentifierValue {
            val normalized = value.trim().uppercase()
            require(normalized.isNotBlank()) {
                "Legal identifier value cannot be blank"
            }
            require(normalized.length <= 100) {
                "Legal identifier value cannot be longer than 100 characters"
            }
            return LegalIdentifierValue(normalized)
        }
    }
}
