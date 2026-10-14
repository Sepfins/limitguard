package io.github.sepfins.limitguard.domain.limit

import io.github.sepfins.limitguard.domain.common.LimitRuleId

data class MatchedLimitRule(
    val id: LimitRuleId,
    val name: LimitRuleName,
    val limitKind: LimitKind,
    val amount: LimitAmount,
    val priority: LimitPriority
)
