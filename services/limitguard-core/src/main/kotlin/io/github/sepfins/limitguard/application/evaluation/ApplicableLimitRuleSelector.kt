package io.github.sepfins.limitguard.application.evaluation

import io.github.sepfins.limitguard.domain.limit.LimitKind
import io.github.sepfins.limitguard.domain.limit.LimitUsageConsumptionMode

/** Selects the exact rule cascade enforced by transaction authorization. */
internal object ApplicableLimitRuleSelector {
    fun select(matchedRules: List<ApplicableLimitRule>): List<ApplicableLimitRule> {
        val selectedPeriodicRuleIds = matchedRules
            .filter { it.configuration.limitKind is LimitKind.Periodic }
            .groupBy { it.rule.ownerPartyProfileId }
            .values
            .flatMap(::selectPeriodicCascade)
            .mapTo(hashSetOf()) { it.rule.id }

        return matchedRules.filter {
            it.configuration.limitKind == LimitKind.PerTransaction || it.rule.id in selectedPeriodicRuleIds
        }
    }

    private fun selectPeriodicCascade(rules: List<ApplicableLimitRule>): List<ApplicableLimitRule> {
        val priorityTiers = rules
            .groupBy { it.configuration.priority }
            .toSortedMap(compareBy { it.value })
        val selected = mutableListOf<ApplicableLimitRule>()

        for (tier in priorityTiers.values) {
            selected += tier
            if (tier.any { it.configuration.usageConsumptionMode == LimitUsageConsumptionMode.ISOLATED }) {
                break
            }
        }

        return selected
    }
}
