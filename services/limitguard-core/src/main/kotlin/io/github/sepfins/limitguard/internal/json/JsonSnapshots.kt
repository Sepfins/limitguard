package io.github.sepfins.limitguard.internal.json

import io.github.sepfins.limitguard.api.errors.FieldViolation
import io.github.sepfins.limitguard.api.errors.ValidationException
import java.math.BigDecimal
import java.math.BigInteger
import java.util.Collections

private const val MAX_DEPTH = 100

private val SCALAR_TYPES = setOf(
    String::class, Boolean::class, Byte::class, Short::class, Int::class, Long::class,
    BigInteger::class, BigDecimal::class,
)

internal object JsonSnapshots {
    fun objectValue(value: Map<*, *>, field: String = "metadata"): Map<String, Any?> =
        copyObject(value, field, depth = 0)

    fun objectList(value: List<*>, field: String): List<Map<String, Any?>> =
        Collections.unmodifiableList(value.mapIndexed { index, item ->
            if (item !is Map<*, *>) invalid("$field[$index]", "must be a JSON object")
            copyObject(item, "$field[$index]", depth = 1)
        })

    private fun copyObject(value: Map<*, *>, path: String, depth: Int): Map<String, Any?> {
        val result = LinkedHashMap<String, Any?>()
        for ((key, item) in value) {
            if (key !is String) invalid(path, "keys must be strings")
            result[key] = copyValue(item, "$path.$key", depth + 1)
        }
        return Collections.unmodifiableMap(result)
    }

    private fun copyValue(value: Any?, path: String, depth: Int): Any? {
        if (depth > MAX_DEPTH) invalid(path, "is cyclic or nested too deeply")
        return when {
            value == null || value::class in SCALAR_TYPES -> value
            value is Double && value.isFinite() -> value
            value is Float && value.isFinite() -> value
            value is Map<*, *> -> copyObject(value, path, depth)
            value is List<*> -> Collections.unmodifiableList(
                value.mapIndexed { index, item -> copyValue(item, "$path[$index]", depth + 1) }
            )
            else -> invalid(path, "is not a JSON value")
        }
    }

    private fun invalid(field: String, message: String): Nothing =
        throw ValidationException("$field $message", listOf(FieldViolation(field, message)))
}
