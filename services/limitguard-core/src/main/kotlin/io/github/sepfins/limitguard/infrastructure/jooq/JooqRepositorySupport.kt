package io.github.sepfins.limitguard.infrastructure.jooq

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import io.github.sepfins.limitguard.api.errors.ConflictException
import io.github.sepfins.limitguard.api.errors.IdempotencyConflictException
import io.github.sepfins.limitguard.api.errors.InfrastructureException
import io.github.sepfins.limitguard.domain.common.Metadata
import org.jooq.Condition
import org.jooq.Field
import org.jooq.JSONB
import org.jooq.Record
import org.jooq.Table
import org.jooq.exception.DataAccessException
import org.jooq.impl.DSL
import org.postgresql.util.PSQLException
import java.math.BigDecimal

private val gson = Gson()

internal fun Metadata.toJsonb(): JSONB = JSONB.valueOf(gson.toJson(value))

internal fun Map<String, Any?>.toJsonb(): JSONB = JSONB.valueOf(gson.toJson(this))

internal fun List<Map<String, Any?>>.toJsonb(): JSONB = JSONB.valueOf(gson.toJson(this))

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
        values
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
        values.mapIndexed { index, value ->
            @Suppress("UNCHECKED_CAST")
            value as? Map<String, Any?>
                ?: throw InfrastructureException("Cannot map non-object entry $index in JSONB $description")
        }
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

internal fun postgresEnum(value: String, typeName: String): Field<String> =
    DSL.field("{0}::$typeName", String::class.java, DSL.`val`(value))

internal fun nullablePostgresEnum(value: String?, typeName: String): Field<String> =
    DSL.field("{0}::$typeName", String::class.java, DSL.`val`(value, String::class.java))

internal fun jooqTable(name: String): Table<Record> = DSL.table(DSL.name(name))

internal fun <T> jooqField(table: String, name: String, type: Class<T>): Field<T> =
    DSL.field(DSL.name(table, name), type)

internal fun Field<String>.eqPostgresEnum(value: String, typeName: String): Condition =
    eq(postgresEnum(value, typeName))

internal fun Field<String>.inPostgresEnums(values: Collection<String>, typeName: String): Condition {
    require(values.isNotEmpty()) { "PostgreSQL enum values cannot be empty" }
    return values
        .map { eqPostgresEnum(it, typeName) }
        .reduce(Condition::or)
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
