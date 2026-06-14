package io.github.sepfins.limitguard.domain.limit

import io.github.sepfins.limitguard.domain.common.LimitRuleId
import io.github.sepfins.limitguard.domain.common.Money

data class LimitViolation(
    val code: String,
    val message: String,
    val limitRuleId: LimitRuleId?,
    val limitAmount: Money?,
    val attemptedAmount: Money,
    val remainingAmount: Money?
)
