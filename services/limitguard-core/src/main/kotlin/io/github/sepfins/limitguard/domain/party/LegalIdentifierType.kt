package io.github.sepfins.limitguard.domain.party

@JvmInline
value class LegalIdentifierType private constructor(val value: String) {
    companion object {
        private val TYPE_PATTERN = Regex("^[A-Z][A-Z0-9_]{0,99}$")

        val CODICE_FISCALE: LegalIdentifierType = of("CODICE_FISCALE")
        val PARTITA_IVA: LegalIdentifierType = of("PARTITA_IVA")

        fun of(value: String): LegalIdentifierType {
            val normalized = value.trim().uppercase()
            require(TYPE_PATTERN.matches(normalized)) {
                "Legal identifier type must start with an uppercase letter and contain only uppercase letters, digits, or underscores"
            }
            return LegalIdentifierType(normalized)
        }
    }
}
