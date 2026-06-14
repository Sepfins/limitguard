package io.github.sepfins.limitguard.domain.common

import java.util.UUID

@JvmInline
value class LimitRuleRevisionId(val id: UUID) {
    companion object {
        fun of(id: String): LimitRuleRevisionId = LimitRuleRevisionId(UUID.fromString(id))
    }
}
