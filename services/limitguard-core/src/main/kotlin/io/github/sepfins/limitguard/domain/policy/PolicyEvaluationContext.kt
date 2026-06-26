package io.github.sepfins.limitguard.domain.policy

import io.github.sepfins.limitguard.domain.change.LimitChangePolicyPhase
import io.github.sepfins.limitguard.domain.change.LimitRuleChangeRequest
import io.github.sepfins.limitguard.domain.common.TenantId
import io.github.sepfins.limitguard.domain.common.Metadata
import kotlin.time.Instant

data class PolicyEvaluationContext(
    val tenantId: TenantId,
    val phase: LimitChangePolicyPhase,
    val changeRequest: LimitRuleChangeRequest,
    val currentRule: Metadata? = null,
    val facts: Metadata = Metadata.EMPTY,
    val evaluatedAt: Instant
)
