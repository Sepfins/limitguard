package io.github.sepfins.limitguard.infrastructure.policy.jsonlogic

import io.github.sepfins.limitguard.domain.change.LimitChangePolicy
import io.github.sepfins.limitguard.domain.change.LimitChangePolicyDecision
import io.github.sepfins.limitguard.domain.change.LimitChangePolicyEngine
import io.github.sepfins.limitguard.domain.common.CurrencyCode
import java.time.Duration
import java.time.Instant

internal class JsonLogicPolicyValidator {
    fun validate(policy: LimitChangePolicy) {
        require(policy.engine == LimitChangePolicyEngine.JSON_LOGIC) {
            "JSON Logic validator cannot validate ${policy.engine.name} policies"
        }
        require(policy.appliesTo.value.isEmpty()) {
            "appliesTo is not executable yet; express policy scope in conditionExpression"
        }

        val budget = ValidationBudget()
        if (policy.conditionExpression.value.isNotEmpty()) {
            validateExpression(policy.conditionExpression.value, "conditionExpression", budget, depth = 0)
        }

        require(policy.actionExpression.size <= MAX_ACTIONS) {
            "actionExpression supports at most $MAX_ACTIONS actions"
        }
        require(policy.actionExpression.isEmpty() || policy.decision != LimitChangePolicyDecision.DENY) {
            "DENY policies must not mutate a request"
        }
        policy.actionExpression.forEachIndexed { index, action ->
            validateAction(action, "actionExpression[$index]", budget)
        }
    }

    private fun validateAction(
        action: Map<String, Any?>,
        path: String,
        budget: ValidationBudget,
    ) {
        val type = action["type"] as? String
            ?: throw IllegalArgumentException("$path.type must be a string")
        when (type) {
            SET_EFFECTIVE_AT -> {
                requireOnlyFields(action, path, setOf("type", "instant", "after"))
                // A policy either names the delay itself or takes the instant from the host's facts.
                require(action.containsKey("instant") != action.containsKey("after")) {
                    "$path needs exactly one of instant or after"
                }
                if (action.containsKey("after")) {
                    validateStringOrExpression(action.getValue("after"), "$path.after", budget) { value ->
                        val delay = runCatching { Duration.parse(value) }.getOrNull()
                        require(delay != null && delay > Duration.ZERO) { "$path.after must be a positive ISO-8601 duration" }
                    }
                } else {
                    validateStringOrExpression(action.getValue("instant"), "$path.instant", budget) { value ->
                        require(runCatching { Instant.parse(value) }.isSuccess) { "$path.instant must be an ISO-8601 instant" }
                    }
                }
            }
            CAP_REQUESTED_AMOUNT -> {
                requireOnlyFields(action, path, setOf("type", "amount", "currency"))
                require(action.containsKey("amount")) { "$path.amount is required" }
                validateStringOrExpression(action.getValue("amount"), "$path.amount", budget) { value ->
                    require(runCatching { value.toBigDecimal() }.isSuccess) { "$path.amount must be a decimal string" }
                }
                if (action.containsKey("currency")) {
                    validateStringOrExpression(action.getValue("currency"), "$path.currency", budget) { value ->
                        CurrencyCode.of(value)
                    }
                }
            }
            else -> throw IllegalArgumentException("$path.type is not supported: $type")
        }
    }

    private fun requireOnlyFields(
        action: Map<String, Any?>,
        path: String,
        allowedFields: Set<String>,
    ) {
        val unknownFields = action.keys - allowedFields
        require(unknownFields.isEmpty()) {
            "$path contains unsupported fields: ${unknownFields.sorted().joinToString()}"
        }
    }

    private fun validateStringOrExpression(
        value: Any?,
        path: String,
        budget: ValidationBudget,
        validateLiteral: (String) -> Unit,
    ) {
        when (value) {
            is Map<*, *> -> validateExpression(value.toStringKeyMap(path), path, budget, depth = 0)
            is String -> validateLiteral(value)
            else -> throw IllegalArgumentException("$path must be a string or JSON Logic expression")
        }
    }

    private fun validateExpression(
        expression: Map<String, Any?>,
        path: String,
        budget: ValidationBudget,
        depth: Int,
    ) {
        budget.consume(path, depth)
        require(expression.size == 1) { "$path must contain exactly one JSON Logic operation" }
        val (operation, arguments) = expression.entries.single()
        require(operation in SUPPORTED_OPERATIONS) { "$path uses unsupported JSON Logic operation: $operation" }

        validateNestedExpressions(arguments, "$path.$operation", budget, depth + 1)
        if (operation in INEXACT_COMPARISON_OPERATIONS && containsMoneyVariable(arguments)) {
            throw IllegalArgumentException(
                "$path must use an exact decimal operation for monetary amounts"
            )
        }
        if (operation in ExactDecimalJsonLogicOperations.names) {
            validateExactDecimalArguments(operation, arguments, path)
        }
    }

    private fun validateNestedExpressions(
        value: Any?,
        path: String,
        budget: ValidationBudget,
        depth: Int,
    ) {
        when (value) {
            is Map<*, *> -> validateExpression(value.toStringKeyMap(path), path, budget, depth)
            is List<*> -> {
                budget.consume(path, depth)
                value.forEachIndexed { index, item ->
                    validateNestedExpressions(item, "$path[$index]", budget, depth + 1)
                }
            }
            null, is String, is Number, is Boolean -> budget.consume(path, depth)
            else -> throw IllegalArgumentException("$path contains a non-JSON value: ${value::class.simpleName}")
        }
    }

    private fun validateExactDecimalArguments(
        operation: String,
        arguments: Any?,
        path: String,
    ) {
        val operands = arguments as? List<*>
            ?: throw IllegalArgumentException("$path.$operation arguments must be an array")
        if (operation == ExactDecimalJsonLogicOperations.EQUALS ||
            operation == ExactDecimalJsonLogicOperations.NOT_EQUALS
        ) {
            require(operands.size == 2) { "$path.$operation requires exactly 2 arguments" }
        } else {
            require(operands.size >= 2) { "$path.$operation requires at least 2 arguments" }
        }
        operands.forEachIndexed { index, operand ->
            when (operand) {
                is Map<*, *> -> Unit
                is String -> require(runCatching { operand.toBigDecimal() }.isSuccess) {
                    "$path.$operation[$index] is not a valid decimal string"
                }
                is Number -> throw IllegalArgumentException(
                    "$path.$operation[$index] must use a decimal string instead of a JSON number"
                )
                else -> throw IllegalArgumentException(
                    "$path.$operation[$index] must be a decimal string or JSON Logic expression"
                )
            }
        }
    }

    private fun containsMoneyVariable(value: Any?): Boolean =
        when (value) {
            is Map<*, *> -> {
                val variablePath = if (value.size == 1 && value.containsKey("var")) {
                    when (val variable = value["var"]) {
                        is String -> variable
                        is List<*> -> variable.firstOrNull() as? String
                        else -> null
                    }
                } else {
                    null
                }
                variablePath in MONEY_PATHS || value.values.any(::containsMoneyVariable)
            }
            is List<*> -> value.any(::containsMoneyVariable)
            else -> false
        }

    private fun Map<*, *>.toStringKeyMap(path: String): Map<String, Any?> {
        require(keys.all { it is String }) { "$path object keys must be strings" }
        @Suppress("UNCHECKED_CAST")
        return this as Map<String, Any?>
    }

    private class ValidationBudget {
        private var nodes: Int = 0

        fun consume(path: String, depth: Int) {
            require(depth <= MAX_DEPTH) { "$path exceeds the maximum JSON Logic depth of $MAX_DEPTH" }
            nodes += 1
            require(nodes <= MAX_NODES) { "policy expression exceeds the maximum size of $MAX_NODES nodes" }
        }
    }

    companion object {
        const val SET_EFFECTIVE_AT = "SET_EFFECTIVE_AT"
        const val CAP_REQUESTED_AMOUNT = "CAP_REQUESTED_AMOUNT"

        private const val MAX_ACTIONS = 16
        private const val MAX_DEPTH = 32
        private const val MAX_NODES = 512

        // The built-in >, >=, < and <= are left out: they compare as doubles, so ordering goes
        // through decimal_*.
        private val SUPPORTED_OPERATIONS = setOf(
            "+", "-", "*", "/", "%", "min", "max",
            "if", "?:", "==", "!=", "===", "!==", "!", "!!", "and", "or",
            "map", "filter", "reduce", "all", "some", "none", "merge", "in",
            "cat", "substr", "missing", "missing_some", "var",
        ) + ExactDecimalJsonLogicOperations.names

        private val INEXACT_COMPARISON_OPERATIONS = setOf("==", "!=", "===", "!==")
        private val MONEY_PATHS = setOf(
            "changeRequest.requestedAmount",
            "changeRequest.beforeAmount",
        )
    }
}
