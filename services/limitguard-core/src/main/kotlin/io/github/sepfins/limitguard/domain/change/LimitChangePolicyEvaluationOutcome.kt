package io.github.sepfins.limitguard.domain.change

enum class LimitChangePolicyEvaluationOutcome {
    MATCHED,
    NOT_MATCHED,
    APPLIED,
    DENIED,
    ERROR,
}
