package io.github.sepfins.limitguard.domain.common

import java.util.UUID

@JvmInline
value class LimitChangePolicyEvaluationId(val value: UUID) {
    companion object {
        fun of(value: UUID): LimitChangePolicyEvaluationId = LimitChangePolicyEvaluationId(value)

        fun of(value: String): LimitChangePolicyEvaluationId = of(UUID.fromString(value))
    }
}
