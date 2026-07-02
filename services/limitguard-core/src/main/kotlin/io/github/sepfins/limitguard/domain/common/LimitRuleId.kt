package io.github.sepfins.limitguard.domain.common

import java.util.UUID

@JvmInline
value class LimitRuleId(val value: UUID) {
    companion object {
        fun of(value: UUID): LimitRuleId = LimitRuleId(value)

        fun of(value: String): LimitRuleId = of(UUID.fromString(value))
    }
}
