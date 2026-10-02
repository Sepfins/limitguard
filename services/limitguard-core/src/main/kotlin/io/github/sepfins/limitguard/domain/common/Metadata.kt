package io.github.sepfins.limitguard.domain.common

import io.github.sepfins.limitguard.internal.json.JsonSnapshots

class Metadata(value: Map<String, Any?>) {
    val value: Map<String, Any?> = JsonSnapshots.objectValue(value)

    override fun equals(other: Any?): Boolean = other is Metadata && value == other.value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = "Metadata(value=$value)"

    companion object {
        val EMPTY: Metadata = Metadata(emptyMap())
    }
}
