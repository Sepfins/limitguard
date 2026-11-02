package io.github.sepfins.limitguard.application.usage

import io.github.sepfins.limitguard.application.applicability.ApplicableLimitRule
import io.github.sepfins.limitguard.domain.common.Money
import io.github.sepfins.limitguard.domain.common.UsageWindow
import io.github.sepfins.limitguard.domain.limit.LimitConfiguration
import io.github.sepfins.limitguard.domain.limit.LimitRule
import io.github.sepfins.limitguard.domain.usage.LimitUsageBucket

/** Available Limit Capacity: what one applicable rule still admits for its assignee at an instant. */
internal data class AvailableLimitCapacity(
    val applicable: ApplicableLimitRule,
    /** The Usage Window of a periodic rule; null for a per-transaction rule. */
    val window: UsageWindow?,
    /** The Usage Bucket already counting the window; null before the first reservation or for a per-transaction rule. */
    val bucket: LimitUsageBucket?,
    /** The amount the rule still admits; null for an unlimited rule, which admits anything. */
    val remaining: Money?,
) {
    val rule: LimitRule get() = applicable.rule
    val configuration: LimitConfiguration get() = applicable.configuration
    val isPeriodic: Boolean get() = window != null

    /** Usage reserved in the window so far; null for a per-transaction rule. */
    val reserved: Money? get() = window?.let { bucket?.reservedAmount ?: Money.zero(rule.amount.currency) }

    /** Usage consumed in the window so far; null for a per-transaction rule. */
    val consumed: Money? get() = window?.let { bucket?.consumedAmount ?: Money.zero(rule.amount.currency) }

    fun admits(amount: Money): Boolean = remaining == null || amount <= remaining
}
