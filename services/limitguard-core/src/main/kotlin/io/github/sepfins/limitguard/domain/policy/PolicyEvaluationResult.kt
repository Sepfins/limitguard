package io.github.sepfins.limitguard.domain.policy

import io.github.sepfins.limitguard.domain.change.LimitChangePolicyDecision
import io.github.sepfins.limitguard.domain.change.LimitChangePolicyEvaluationOutcome
import io.github.sepfins.limitguard.domain.common.Metadata

data class PolicyEvaluationResult(
    val matched: Boolean,
    val outcome: LimitChangePolicyEvaluationOutcome,
    val decision: LimitChangePolicyDecision,
    val actionResult: Metadata = Metadata.EMPTY,
    val message: String? = null,
    val actions: List<PolicyAction> = emptyList(),
)
