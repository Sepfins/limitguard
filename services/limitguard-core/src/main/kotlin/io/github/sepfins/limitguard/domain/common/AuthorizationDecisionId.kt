package io.github.sepfins.limitguard.domain.common

import java.util.UUID

@JvmInline
value class AuthorizationDecisionId private constructor(val value: UUID) {
    companion object {
        fun of(value: UUID): AuthorizationDecisionId = AuthorizationDecisionId(value)
    }
}
