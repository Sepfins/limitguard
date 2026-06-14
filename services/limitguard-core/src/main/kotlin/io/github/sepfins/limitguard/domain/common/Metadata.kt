package io.github.sepfins.limitguard.domain.common

@JvmInline
value class Metadata(val value: Map<String, Any?>) {
    companion object {
        val EMPTY: Metadata = Metadata(emptyMap())
    }
}
