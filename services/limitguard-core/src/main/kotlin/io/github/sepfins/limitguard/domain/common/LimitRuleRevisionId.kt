package io.github.sepfins.limitguard.domain.common

import java.util.UUID

@JvmInline
value class LimitRuleRevisionId(val value: UUID) {
    companion object {
        fun of(value: UUID): LimitRuleRevisionId = LimitRuleRevisionId(value)

        fun of(value: String): LimitRuleRevisionId = of(UUID.fromString(value))
    }
}
