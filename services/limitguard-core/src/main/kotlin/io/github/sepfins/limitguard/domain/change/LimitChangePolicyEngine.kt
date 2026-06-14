package io.github.sepfins.limitguard.domain.change

enum class LimitChangePolicyEngine {
    JSON_LOGIC,
    CEL,
    SQL_FUNCTION,
    EXTERNAL_DECISION,
}
