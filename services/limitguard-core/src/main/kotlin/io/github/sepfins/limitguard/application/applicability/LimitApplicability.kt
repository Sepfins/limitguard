package io.github.sepfins.limitguard.application.applicability

import io.github.sepfins.limitguard.domain.common.LimitRuleId
import java.time.ZoneId

/**
 * Limit Applicability: whether the acting party may operate the funding source and which
 * limit cascade governs the operation at an instant. Authorization and operation capacity
 * both map this outcome; neither decides authority or cascade on its own.
 */
internal sealed interface LimitApplicability {
    /** Authorities resolved before the outcome was reached; empty when authority was never established. */
    val authorities: List<ResolvedFundingAuthority>

    /** The tenant's zone, in which the cascade's usage windows open. */
    val timeZone: ZoneId

    /** The operation cannot proceed; `cascade` carries the selected rules when they were reached (currency mismatch). */
    data class Denied(
        val reasons: List<ApplicabilityReason>,
        override val timeZone: ZoneId,
        val cascade: List<ApplicableLimitRule> = emptyList(),
        override val authorities: List<ResolvedFundingAuthority> = emptyList(),
    ) : LimitApplicability

    /** No rule matches and the tenant leaves the decision to the host. */
    data class NotApplicable(
        override val authorities: List<ResolvedFundingAuthority>,
        override val timeZone: ZoneId,
    ) : LimitApplicability

    /** No rule matches and the tenant allows the operation. */
    data class Unrestricted(
        override val authorities: List<ResolvedFundingAuthority>,
        override val timeZone: ZoneId,
    ) : LimitApplicability

    /** The selected cascade governs the operation; unlimited rules are included but cap nothing. */
    data class Applicable(
        val cascade: List<ApplicableLimitRule>,
        override val authorities: List<ResolvedFundingAuthority>,
        override val timeZone: ZoneId,
    ) : LimitApplicability
}

internal data class ApplicabilityReason(
    val code: String,
    val message: String,
    val limitRuleId: LimitRuleId? = null,
)
