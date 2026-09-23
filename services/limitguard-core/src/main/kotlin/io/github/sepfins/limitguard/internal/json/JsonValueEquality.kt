package io.github.sepfins.limitguard.internal.json

import java.math.BigDecimal

/**
 * Compares values that have crossed a JSON boundary without relying on the JVM
 * implementation class chosen for a JSON number.
 */
internal object JsonValueEquality {
    fun objectsEqual(
        left: Map<String, Any?>,
        right: Map<String, Any?>,
    ): Boolean = valuesEqual(left, right)

    fun valuesEqual(left: Any?, right: Any?): Boolean {
        if (left === right) return true
        if (left == null || right == null) return false

        return when {
            left is Number && right is Number -> numbersEqual(left, right)
            left is Map<*, *> && right is Map<*, *> -> mapsEqual(left, right)
            left is List<*> && right is List<*> -> listsEqual(left, right)
            else -> left == right
        }
    }

    private fun numbersEqual(left: Number, right: Number): Boolean =
        runCatching {
            BigDecimal(left.toString()).compareTo(BigDecimal(right.toString())) == 0
        }.getOrDefault(false)

    private fun mapsEqual(left: Map<*, *>, right: Map<*, *>): Boolean =
        left.size == right.size &&
            left.keys.all { key -> right.containsKey(key) && valuesEqual(left[key], right[key]) }

    private fun listsEqual(left: List<*>, right: List<*>): Boolean =
        left.size == right.size && left.indices.all { index -> valuesEqual(left[index], right[index]) }
}
