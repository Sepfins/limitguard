package io.github.sepfins.limitguard.domain.change

enum class LimitRuleChangeEventType {
    REQUESTED,
    POLICY_EVALUATED,
    APPROVED,
    SCHEDULED,
    APPLIED,
    REJECTED,
    CANCELLED,
    SUPERSEDED,
    FAILED,
}
