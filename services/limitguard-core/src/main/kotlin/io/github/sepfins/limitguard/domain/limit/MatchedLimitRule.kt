package io.github.sepfins.limitguard.domain.limit

import io.github.sepfins.limitguard.domain.common.LimitRuleId
import io.github.sepfins.limitguard.domain.common.Money

data class MatchedLimitRule(
    val id: LimitRuleId,
    val name: LimitRuleName,
    val limitKind: LimitKind,
    val amount: Money,
    val priority: LimitPriority
)
