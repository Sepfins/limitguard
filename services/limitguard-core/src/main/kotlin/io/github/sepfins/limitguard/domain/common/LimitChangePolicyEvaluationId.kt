package io.github.sepfins.limitguard.domain.common

import java.util.UUID

@JvmInline
value class LimitChangePolicyEvaluationId(val id: UUID) {
    companion object {
        fun of(id: String): LimitChangePolicyEvaluationId = LimitChangePolicyEvaluationId(UUID.fromString(id))
    }
}
