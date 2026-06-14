package io.github.sepfins.limitguard.domain.common

import java.util.UUID

@JvmInline
value class LimitRuleChangeEventId(val id: UUID) {
    companion object {
        fun of(id: String): LimitRuleChangeEventId = LimitRuleChangeEventId(UUID.fromString(id))
    }
}
