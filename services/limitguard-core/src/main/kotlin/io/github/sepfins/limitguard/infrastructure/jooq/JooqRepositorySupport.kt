package io.github.sepfins.limitguard.infrastructure.jooq

import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import io.github.sepfins.limitguard.api.errors.ConflictException
import io.github.sepfins.limitguard.api.errors.IdempotencyConflictException
import io.github.sepfins.limitguard.api.errors.InfrastructureException
import io.github.sepfins.limitguard.domain.common.Metadata
import io.github.sepfins.limitguard.internal.json.JsonSnapshots
import org.jooq.Condition
import org.jooq.Field
import org.jooq.JSONB
import org.jooq.exception.DataAccessException
import org.postgresql.util.PSQLException
import java.math.BigDecimal

private val gson = GsonBuilder().serializeNulls().create()

internal fun Metadata.toJsonb(): JSONB = JSONB.valueOf(gson.toJson(value))

internal fun Map<String, Any?>.toJsonb(): JSONB = JSONB.valueOf(gson.toJson(JsonSnapshots.objectValue(this)))

internal fun List<Map<String, Any?>>.toJsonb(): JSONB =
    JSONB.valueOf(gson.toJson(JsonSnapshots.objectList(this, "actions")))

internal fun JSONB?.toMetadata(): Metadata {
    return Metadata(toObjectMap("metadata"))
}

internal fun JSONB?.toObjectMap(description: String): Map<String, Any?> {
    if (this == null) throw InfrastructureException("Cannot map null JSONB $description")

    return try {
        val parsed = JsonParser.parseString(data()).toKotlinValue()
        @Suppress("UNCHECKED_CAST")
        val values = parsed as? Map<String, Any?>
            ?: throw InfrastructureException("Cannot map non-object JSONB $description")
        JsonSnapshots.objectValue(values, description)
    } catch (exception: InfrastructureException) {
        throw exception
    } catch (exception: RuntimeException) {
        throw InfrastructureException("Cannot map JSONB $description", exception)
    }
}

internal fun JSONB?.toObjectList(description: String): List<Map<String, Any?>> {
    if (this == null) throw InfrastructureException("Cannot map null JSONB $description")

    return try {
        val parsed = JsonParser.parseString(data()).toKotlinValue()
        val values = parsed as? List<*>
            ?: throw InfrastructureException("Cannot map non-array JSONB $description")
        JsonSnapshots.objectList(values, description)
    } catch (exception: InfrastructureException) {
        throw exception
    } catch (exception: RuntimeException) {
        throw InfrastructureException("Cannot map JSONB $description", exception)
    }
}

private fun JsonElement.toKotlinValue(): Any? = when {
    isJsonNull -> null
    isJsonObject -> asJsonObject.entrySet().associate { (key, value) -> key to value.toKotlinValue() }
    isJsonArray -> asJsonArray.map { it.toKotlinValue() }
    asJsonPrimitive.isBoolean -> asBoolean
    asJsonPrimitive.isString -> asString
    else -> numberValue(asString)
}

private fun numberValue(value: String): Number {
    val decimal = BigDecimal(value)
    if (decimal.scale() <= 0) {
        runCatching { return decimal.intValueExact() }
        runCatching { return decimal.longValueExact() }
    }
    return decimal
}

internal fun Field<String>.containsIgnoreCaseLiteral(search: String): Condition {
    val escaped = search
        .replace("\\", "\\\\")
        .replace("%", "\\%")
        .replace("_", "\\_")
    return likeIgnoreCase("%$escaped%", '\\')
}

internal fun <T> jooqOperation(
    operation: String,
    idempotencyConstraint: String? = null,
    block: () -> T,
): T =
    try {
        block()
    } catch (exception: DataAccessException) {
        when {
            exception.sqlState() == UNIQUE_VIOLATION_SQL_STATE &&
                idempotencyConstraint != null &&
                exception.postgresConstraintName() == idempotencyConstraint ->
                throw IdempotencyConflictException("$operation conflicts with an existing idempotency key", exception)
            exception.isIntegrityConstraintViolation() ->
                throw ConflictException("$operation conflicts with existing data", exception)
            else -> throw InfrastructureException("$operation failed", exception)
        }
    }

private const val UNIQUE_VIOLATION_SQL_STATE = "23505"
private const val INTEGRITY_CONSTRAINT_SQL_STATE_CLASS = "23"

private fun DataAccessException.isIntegrityConstraintViolation(): Boolean =
    sqlState().startsWith(INTEGRITY_CONSTRAINT_SQL_STATE_CLASS)

private fun DataAccessException.postgresConstraintName(): String? =
    getCause(PSQLException::class.java)?.serverErrorMessage?.constraint
