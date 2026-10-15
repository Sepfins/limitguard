package io.github.sepfins.limitguard.application.evaluation

import io.github.sepfins.limitguard.domain.limit.LimitConfiguration
import io.github.sepfins.limitguard.domain.limit.LimitRule

/** An owner's rule matched through the configuration that covers the transaction. */
internal data class ApplicableLimitRule(
    val rule: LimitRule,
    val configuration: LimitConfiguration,
)
