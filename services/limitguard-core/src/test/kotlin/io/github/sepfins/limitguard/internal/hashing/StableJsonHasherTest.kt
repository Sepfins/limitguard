package io.github.sepfins.limitguard.internal.hashing

import kotlin.test.Test
import kotlin.test.assertEquals

class StableJsonHasherTest {
    @Test
    fun `sha256 digest uses stable lowercase hex`() {
        assertEquals(
            "015abd7f5cc57a2dd94b7590f04ad8084273905ee33ec5cebeae62276a97f862",
            StableJsonHasher.sha256Hex("{\"a\":1}"),
        )
    }
}
