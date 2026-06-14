package io.github.sepfins.limitguard.domain.change

enum class LimitChangePolicyDecision {
    ALLOW,
    DENY,
    MUTATE_REQUEST,
    SCHEDULE_EFFECTIVE_AT,
    CANCEL_PENDING,
    CREATE_DEFAULT,
    REQUIRE_APPROVAL,
    NO_OP,
}
