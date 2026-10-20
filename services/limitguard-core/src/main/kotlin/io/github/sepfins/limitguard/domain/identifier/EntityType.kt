package io.github.sepfins.limitguard.domain.identifier

@JvmInline
value class EntityType private constructor(val value: String) {
    companion object {
        val PARTY_PROFILE: EntityType = EntityType("PARTY_PROFILE")
        val FUNDING_SOURCE: EntityType = EntityType("FUNDING_SOURCE")

        fun of(value: String): EntityType =
            when (value.trim()) {
                PARTY_PROFILE.value -> PARTY_PROFILE
                FUNDING_SOURCE.value -> FUNDING_SOURCE
                else -> throw IllegalArgumentException("unsupported entity type: $value")
            }
    }
}
