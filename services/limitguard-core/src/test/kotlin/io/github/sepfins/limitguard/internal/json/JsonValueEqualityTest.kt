package io.github.sepfins.limitguard.internal.json

import java.math.BigDecimal
import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JsonValueEqualityTest {
    @Test
    fun `nested maps and lists compare numeric values independently of JVM number type`() {
        val persisted = mapOf(
            "nested" to mapOf(
                "items" to listOf(1, mapOf("attempt" to 2L)),
            ),
        )
        val requested = mapOf(
            "nested" to mapOf(
                "items" to listOf(1L, mapOf("attempt" to 2)),
            ),
        )

        assertTrue(JsonValueEquality.objectsEqual(persisted, requested))
        assertFalse(
            JsonValueEquality.objectsEqual(
                persisted,
                mapOf("nested" to mapOf("items" to listOf(1L, mapOf("attempt" to 3)))),
            ),
        )
    }

    @Test
    fun `genuinely different numbers do not compare equal`() {
        assertFalse(JsonValueEquality.valuesEqual(1, 2L))
        assertFalse(JsonValueEquality.valuesEqual(BigInteger("9007199254740993"), BigInteger("9007199254740994")))
        assertFalse(
            JsonValueEquality.valuesEqual(
                BigDecimal("0.123456789012345678901234567890"),
                BigDecimal("0.123456789012345678901234567891"),
            ),
        )
    }

    @Test
    fun `equivalent numbers compare by value rather than type or decimal scale`() {
        assertTrue(JsonValueEquality.valuesEqual(BigInteger("9007199254740993"), BigDecimal("9007199254740993.00")))
        assertTrue(
            JsonValueEquality.valuesEqual(
                BigDecimal("1.0"),
                BigDecimal("1.000000000000000000"),
            ),
        )
    }
}
