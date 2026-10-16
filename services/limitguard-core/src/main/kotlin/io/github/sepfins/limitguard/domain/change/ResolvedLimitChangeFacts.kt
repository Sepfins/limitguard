package io.github.sepfins.limitguard.domain.change

import io.github.sepfins.limitguard.domain.limit.LimitConfiguration
import io.github.sepfins.limitguard.domain.limit.LimitRule

data class ResolvedLimitChangeFacts(
    val currentRule: LimitRule?,
    val configuration: LimitConfiguration,
    val ancestorConfigurations: List<LimitConfiguration> = emptyList(),
)
