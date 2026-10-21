package io.github.sepfins.limitguard.domain.common

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MoneyTest {
    @Test
    fun `currency code must match schema format`() {
        assertFailsWith<IllegalArgumentException> {
            CurrencyCode.of("eur")
        }
        assertFailsWith<IllegalArgumentException> {
            CurrencyCode.of("EURO")
        }
    }

    @Test
    fun `money normalizes to schema scale using half up rounding`() {
        val eur = CurrencyCode.of("EUR")

        assertEquals("10.0000".toBigDecimal(), Money.of("10", eur).amount)
        assertEquals("10.1235".toBigDecimal(), Money.of("10.12345", eur).amount)
    }

    @Test
    fun `positive money rejects zero`() {
        assertFailsWith<IllegalArgumentException> {
            Money.positive("0", CurrencyCode.of("EUR"))
        }
    }

    @Test
    fun `money comparison rejects mixed currencies`() {
        assertFailsWith<IllegalArgumentException> {
            Money.of("1", CurrencyCode.of("EUR")) < Money.of("1", CurrencyCode.of("USD"))
        }
    }
}
