package io.github.sepfins.limitguard.domain.change

import io.github.sepfins.limitguard.domain.common.LimitChangePolicyEvaluationId
import io.github.sepfins.limitguard.domain.common.LimitChangePolicyId
import io.github.sepfins.limitguard.domain.common.LimitChangeRequestId
import io.github.sepfins.limitguard.domain.common.Metadata
import io.github.sepfins.limitguard.domain.common.TenantId
import java.time.Instant

data class LimitChangePolicyEvaluation(
    val id: LimitChangePolicyEvaluationId,
    val tenantId: TenantId,
    val changeRequestId: LimitChangeRequestId,
    val policyId: LimitChangePolicyId,
    val phase: LimitChangePolicyPhase,
    val outcome: LimitChangePolicyEvaluationOutcome,
    val decision: LimitChangePolicyDecision,
    val matched: Boolean,
    val policySnapshot: Metadata,
    val inputContext: Metadata = Metadata.EMPTY,
    val actionResult: Metadata = Metadata.EMPTY,
    val message: String? = null,
    val evaluatedAt: Instant? = null
)
