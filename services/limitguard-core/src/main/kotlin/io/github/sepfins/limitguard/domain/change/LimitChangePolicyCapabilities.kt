package io.github.sepfins.limitguard.domain.change

internal object LimitChangePolicyCapabilities {
    val authorizationDecisions = setOf(
        LimitChangePolicyDecision.ALLOW,
        LimitChangePolicyDecision.DENY,
        LimitChangePolicyDecision.REQUIRE_APPROVAL,
        LimitChangePolicyDecision.NO_OP,
    )
}
