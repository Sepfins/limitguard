package io.github.sepfins.limitguard.application.evaluation

import io.github.sepfins.limitguard.domain.limit.LimitKind
import io.github.sepfins.limitguard.domain.limit.LimitRule
import io.github.sepfins.limitguard.domain.limit.LimitUsageConsumptionMode

/** Selects the exact rule cascade enforced by transaction authorization. */
internal object ApplicableLimitRuleSelector {
    fun select(matchedRules: List<LimitRule>): List<LimitRule> {
        val selectedPeriodicRuleIds = matchedRules
            .filter { it.limitKind is LimitKind.Periodic }
            .groupBy { it.ownerPartyProfileId }
            .values
            .flatMap(::selectPeriodicCascade)
            .mapTo(hashSetOf()) { it.id }

        return matchedRules.filter { rule ->
            rule.limitKind == LimitKind.PerTransaction || rule.id in selectedPeriodicRuleIds
        }
    }

    private fun selectPeriodicCascade(rules: List<LimitRule>): List<LimitRule> {
        val priorityTiers = rules
            .groupBy { it.priority }
            .toSortedMap(compareBy { it.value })
        val selected = mutableListOf<LimitRule>()

        for (tier in priorityTiers.values) {
            selected += tier
            if (tier.any { it.usageConsumptionMode == LimitUsageConsumptionMode.ISOLATED }) {
                break
            }
        }

        return selected
    }
}
