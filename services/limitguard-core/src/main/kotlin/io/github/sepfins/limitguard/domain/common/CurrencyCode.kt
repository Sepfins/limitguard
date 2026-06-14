package io.github.sepfins.limitguard.domain.common

import java.util.Currency

@JvmInline
value class CurrencyCode private constructor(val value: String) {
    companion object {
        fun of(value: String): CurrencyCode {
            val normalized = value.trim().uppercase()
            require(normalized.length == 3) { "Currency code must be exactly 3 characters long." }
            runCatching {
                Currency.getInstance(normalized)
            }.getOrElse {
                throw IllegalArgumentException("Invalid ISO 4217 currency code: $value")
            }

            return CurrencyCode(normalized)
        }
    }
}
