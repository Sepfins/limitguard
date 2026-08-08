package io.github.sepfins.limitguard.internal.hashing

import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class CanonicalJsonTest {
    @Test
    fun `canonicalizes object order and numbers according to JCS`() {
        assertEquals(
            """{"a":0.123456789,"large":1e+30,"small":1e-27,"z":1}""",
            CanonicalJson.encodeObject(
                linkedMapOf(
                    "z" to 1,
                    "small" to BigDecimal("0.000000000000000000000000001"),
                    "large" to BigDecimal("1E+30"),
                    "a" to BigDecimal("0.123456789"),
                ),
            ),
        )
    }

    @Test
    fun `does not collapse numbers beyond seven decimal places`() {
        assertNotEquals(
            CanonicalJson.encodeObject(mapOf("value" to BigDecimal("0.12345678"))),
            CanonicalJson.encodeObject(mapOf("value" to BigDecimal("0.12345679"))),
        )
    }
}
