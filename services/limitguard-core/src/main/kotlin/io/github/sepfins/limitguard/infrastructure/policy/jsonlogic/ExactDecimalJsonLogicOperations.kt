package io.github.sepfins.limitguard.infrastructure.policy.jsonlogic

import io.github.jamsesso.jsonlogic.JsonLogic
import java.math.BigDecimal
import java.math.BigInteger

internal object ExactDecimalJsonLogicOperations {
    const val EQUALS = "decimal_eq"
    const val NOT_EQUALS = "decimal_ne"
    const val GREATER_THAN = "decimal_gt"
    const val GREATER_THAN_OR_EQUAL = "decimal_gte"
    const val LESS_THAN = "decimal_lt"
    const val LESS_THAN_OR_EQUAL = "decimal_lte"

    val names: Set<String> = setOf(
        EQUALS,
        NOT_EQUALS,
        GREATER_THAN,
        GREATER_THAN_OR_EQUAL,
        LESS_THAN,
        LESS_THAN_OR_EQUAL,
    )

    fun installInto(jsonLogic: JsonLogic): JsonLogic = jsonLogic.apply {
        addOperation(EQUALS) { arguments ->
            compare(arguments, exactlyTwo = true) { left, right -> left.compareTo(right) == 0 }
        }
        addOperation(NOT_EQUALS) { arguments ->
            compare(arguments, exactlyTwo = true) { left, right -> left.compareTo(right) != 0 }
        }
        addOperation(GREATER_THAN) { arguments -> compare(arguments) { left, right -> left > right } }
        addOperation(GREATER_THAN_OR_EQUAL) { arguments -> compare(arguments) { left, right -> left >= right } }
        addOperation(LESS_THAN) { arguments -> compare(arguments) { left, right -> left < right } }
        addOperation(LESS_THAN_OR_EQUAL) { arguments -> compare(arguments) { left, right -> left <= right } }
    }

    private fun compare(
        arguments: Array<out Any?>,
        exactlyTwo: Boolean = false,
        predicate: (BigDecimal, BigDecimal) -> Boolean,
    ): Boolean {
        if (exactlyTwo) {
            require(arguments.size == 2) { "exact decimal equality requires exactly 2 arguments" }
        } else {
            require(arguments.size >= 2) { "exact decimal comparison requires at least 2 arguments" }
        }
        return arguments
            .mapIndexed { index, value -> value.toExactDecimal(index) }
            .zipWithNext()
            .all { (left, right) -> predicate(left, right) }
    }

    private fun Any?.toExactDecimal(index: Int): BigDecimal =
        when (this) {
            is String -> runCatching { trim().toBigDecimal() }
                .getOrElse { throw IllegalArgumentException("decimal argument $index is not a valid decimal string") }
            is BigDecimal -> this
            is BigInteger -> toBigDecimal()
            else -> throw IllegalArgumentException(
                "decimal argument $index must be a string so its precision is preserved"
            )
        }
}
