package io.github.sepfins.limitguard.domain.party

@JvmInline
value class JurisdictionCountry private constructor(val value: String) {
    companion object {
        private val COUNTRY_PATTERN = Regex("^[A-Z]{2}$")

        fun of(value: String): JurisdictionCountry {
            val normalized = value.trim().uppercase()
            require(COUNTRY_PATTERN.matches(normalized)) {
                "Jurisdiction country must be an ISO 3166-1 alpha-2 uppercase code"
            }
            return JurisdictionCountry(normalized)
        }
    }
}
