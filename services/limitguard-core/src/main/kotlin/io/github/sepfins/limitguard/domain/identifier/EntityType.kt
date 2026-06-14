package io.github.sepfins.limitguard.domain.identifier

@JvmInline
value class EntityType private constructor(val value: String) {
    companion object {
        fun of(value: String): EntityType {
            val normalized = value.trim()
            require(normalized.isNotBlank()) { "EntityType cannot be blank" }
            require(normalized.length <= 50) { "EntityType cannot be longer than 50 characters" }
            return EntityType(normalized)
        }
    }
}
