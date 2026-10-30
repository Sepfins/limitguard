package io.github.sepfins.limitguard.application.applicability

import io.github.sepfins.limitguard.domain.common.LimitRuleId

/**
 * Limit Applicability: whether the acting party may operate the funding source and which
 * limit cascade governs the operation at an instant. Authorization and operation capacity
 * both map this outcome; neither decides authority or cascade on its own.
 */
internal sealed interface LimitApplicability {
    /** Authorities resolved before the outcome was reached; empty when authority was never established. */
    val authorities: List<ResolvedFundingAuthority>

    /** The operation cannot proceed; `cascade` carries the selected rules when they were reached (currency mismatch). */
    data class Denied(
        val reasons: List<ApplicabilityReason>,
        val cascade: List<ApplicableLimitRule> = emptyList(),
        override val authorities: List<ResolvedFundingAuthority> = emptyList(),
    ) : LimitApplicability

    /** No rule matches and the tenant leaves the decision to the host. */
    data class NotApplicable(override val authorities: List<ResolvedFundingAuthority>) : LimitApplicability

    /** No rule matches and the tenant allows the operation. */
    data class Unrestricted(override val authorities: List<ResolvedFundingAuthority>) : LimitApplicability

    /** The selected cascade governs the operation; unlimited rules are included but cap nothing. */
    data class Applicable(
        val cascade: List<ApplicableLimitRule>,
        override val authorities: List<ResolvedFundingAuthority>,
    ) : LimitApplicability
}

internal data class ApplicabilityReason(
    val code: String,
    val message: String,
    val limitRuleId: LimitRuleId? = null,
)
