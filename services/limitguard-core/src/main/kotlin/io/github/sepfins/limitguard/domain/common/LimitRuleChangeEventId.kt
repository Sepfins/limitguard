package io.github.sepfins.limitguard.domain.common

import java.util.UUID

@JvmInline
value class LimitRuleChangeEventId(val value: UUID) {
    companion object {
        fun of(value: UUID): LimitRuleChangeEventId = LimitRuleChangeEventId(value)

        fun of(value: String): LimitRuleChangeEventId = of(UUID.fromString(value))
    }
}
