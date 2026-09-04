package io.github.sepfins.limitguard.domain.tenant

enum class NoMatchingRuleBehavior {
    ALLOW,
    DENY,
    NOT_APPLICABLE,
}
