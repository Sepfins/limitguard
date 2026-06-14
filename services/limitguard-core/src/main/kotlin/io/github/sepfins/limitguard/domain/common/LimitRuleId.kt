package io.github.sepfins.limitguard.domain.common

import java.util.UUID

@JvmInline
value class LimitRuleId(val id: UUID) {
    companion object {
        fun of(id: String): LimitRuleId = LimitRuleId(UUID.fromString(id))
    }
}
