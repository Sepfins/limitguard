package io.github.sepfins.limitguard.application.applicability

import io.github.sepfins.limitguard.domain.limit.LimitConfiguration
import io.github.sepfins.limitguard.domain.limit.LimitRule

/** An owner's rule matched through the configuration that covers the operation. */
internal data class ApplicableLimitRule(
    val rule: LimitRule,
    val configuration: LimitConfiguration,
)
