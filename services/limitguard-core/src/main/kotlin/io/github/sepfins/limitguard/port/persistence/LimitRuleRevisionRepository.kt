package io.github.sepfins.limitguard.port.persistence

import io.github.sepfins.limitguard.domain.change.LimitRuleRevision
import io.github.sepfins.limitguard.domain.common.LimitChangeRequestId
import io.github.sepfins.limitguard.domain.common.LimitRuleId
import io.github.sepfins.limitguard.domain.common.LimitRuleRevisionId
import io.github.sepfins.limitguard.domain.common.TenantId

interface LimitRuleRevisionRepository {
    fun findById(tenantId: TenantId, id: LimitRuleRevisionId): LimitRuleRevision?
    fun findByLimitRule(tenantId: TenantId, limitRuleId: LimitRuleId): List<LimitRuleRevision>
    fun findByChangeRequest(tenantId: TenantId, changeRequestId: LimitChangeRequestId): List<LimitRuleRevision>
    fun insert(tenantId: TenantId, revision: LimitRuleRevision): LimitRuleRevision
}
