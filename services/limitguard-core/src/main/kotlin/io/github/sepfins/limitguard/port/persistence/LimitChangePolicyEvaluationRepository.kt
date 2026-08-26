package io.github.sepfins.limitguard.port.persistence

import io.github.sepfins.limitguard.domain.change.LimitChangePolicyEvaluation
import io.github.sepfins.limitguard.domain.common.LimitChangePolicyEvaluationId
import io.github.sepfins.limitguard.domain.common.LimitChangeRequestId
import io.github.sepfins.limitguard.domain.common.TenantId

interface LimitChangePolicyEvaluationRepository {
    fun findById(
        tenantId: TenantId,
        id: LimitChangePolicyEvaluationId,
    ): LimitChangePolicyEvaluation?
    fun findByChangeRequest(
        tenantId: TenantId,
        changeRequestId: LimitChangeRequestId,
    ): List<LimitChangePolicyEvaluation>
    fun insert(
        tenantId: TenantId,
        evaluation: LimitChangePolicyEvaluation,
    ): LimitChangePolicyEvaluation
}
