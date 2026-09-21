package io.github.sepfins.limitguard.infrastructure.jooq

import io.github.sepfins.limitguard.domain.common.Metadata
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals

class JooqJsonTest {
    @Test
    fun `JSONB writers preserve explicit nulls and exact decimals in nested objects and arrays`() {
        val values = mapOf(
            "optional" to null,
            "items" to listOf(null, mapOf("optional" to null, "amount" to BigDecimal("9007199254740993.1234"))),
        )

        assertEquals(values, Metadata(values).toJsonb().toMetadata().value)
        assertEquals(values, values.toJsonb().toObjectMap("test"))
        assertEquals(listOf(values), listOf(values).toJsonb().toObjectList("test"))
    }
}
