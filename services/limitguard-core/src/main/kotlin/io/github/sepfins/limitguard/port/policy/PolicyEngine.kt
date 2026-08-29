package io.github.sepfins.limitguard.port.policy

import io.github.sepfins.limitguard.domain.change.LimitChangePolicy
import io.github.sepfins.limitguard.domain.change.LimitChangePolicyEngine
import io.github.sepfins.limitguard.domain.policy.PolicyEvaluationContext
import io.github.sepfins.limitguard.domain.policy.PolicyEvaluationResult

interface PolicyEngine {
    val engine: LimitChangePolicyEngine

    /** Validate engine-specific policy syntax before the policy is persisted. */
    fun validate(policy: LimitChangePolicy) = Unit

    fun evaluate(policy: LimitChangePolicy, context: PolicyEvaluationContext): PolicyEvaluationResult
}
