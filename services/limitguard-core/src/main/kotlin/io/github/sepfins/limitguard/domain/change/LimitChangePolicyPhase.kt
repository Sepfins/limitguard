package io.github.sepfins.limitguard.domain.change

enum class LimitChangePolicyPhase {
    REQUEST_VALIDATION,
    AUTHORIZATION,
    DEFAULTING,
    SCHEDULING,
    CONFLICT_RESOLUTION,
    PRE_APPLY,
    POST_APPLY,
}
