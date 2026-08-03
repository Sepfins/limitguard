package io.github.sepfins.limitguard.application.usage

import io.github.sepfins.limitguard.domain.limit.LimitRule
import io.github.sepfins.limitguard.domain.usage.LimitUsageBucket

internal data class UsageReservationCandidate(
    val rule: LimitRule,
    val existingBucket: LimitUsageBucket?,
    val candidateBucket: LimitUsageBucket,
) {
    val isNew: Boolean
        get() = existingBucket == null
}
