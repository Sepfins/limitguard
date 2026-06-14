package io.github.sepfins.limitguard.domain.change

enum class LimitRuleChangeStatus {
    REQUESTED,
    APPROVED,
    PENDING_EFFECTIVE_DATE,
    APPLIED,
    REJECTED,
    CANCELLED,
    SUPERSEDED,
    FAILED,
}
