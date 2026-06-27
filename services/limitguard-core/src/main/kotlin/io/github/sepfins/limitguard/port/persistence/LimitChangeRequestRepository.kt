package io.github.sepfins.limitguard.port.persistence

import io.github.sepfins.limitguard.domain.change.LimitRuleChangeRequest
import io.github.sepfins.limitguard.domain.change.RequestActorContext
import io.github.sepfins.limitguard.domain.common.LimitChangeRequestId
import io.github.sepfins.limitguard.domain.common.LimitRuleId
import io.github.sepfins.limitguard.domain.common.PartyProfileId
import io.github.sepfins.limitguard.domain.common.TenantId

interface LimitChangeRequestRepository {
    fun findById(tenantId: TenantId, id: LimitChangeRequestId): LimitRuleChangeRequest?
    fun findByLimitRule(tenantId: TenantId, limitRuleId: LimitRuleId): List<LimitRuleChangeRequest>
    fun findByRequesterContext(tenantId: TenantId, requestActorContext: RequestActorContext): List<LimitRuleChangeRequest>
    fun findByRequester(tenantId: TenantId, requester: PartyProfileId): List<LimitRuleChangeRequest>
    fun insert(tenantId: TenantId, request: LimitRuleChangeRequest): LimitRuleChangeRequest
    fun update(tenantId: TenantId, request: LimitRuleChangeRequest): LimitRuleChangeRequest
}