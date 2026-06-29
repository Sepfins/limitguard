package io.github.sepfins.limitguard.domain.common

@JvmInline
value class CurrencyCode private constructor(val value: String) {
    companion object {
        private val CURRENCY_PATTERN = Regex("^[A-Z]{3}$")

        fun of(value: String): CurrencyCode {
            val normalized = value.trim()
            require(CURRENCY_PATTERN.matches(normalized)) {
                "Currency code must be exactly 3 uppercase ASCII letters"
            }
            return CurrencyCode(normalized)
        }
    }
}
