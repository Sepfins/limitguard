package io.github.sepfins.limitguard.port.persistence

import io.github.sepfins.limitguard.domain.change.LimitRuleChangeRequest
import io.github.sepfins.limitguard.domain.change.RequestActorContext
import io.github.sepfins.limitguard.domain.change.ScopeHash
import io.github.sepfins.limitguard.domain.common.IdempotencyKey
import io.github.sepfins.limitguard.domain.common.LimitChangeRequestId
import io.github.sepfins.limitguard.domain.common.LimitRuleId
import io.github.sepfins.limitguard.domain.common.PartyProfileId
import io.github.sepfins.limitguard.domain.common.TenantId
import java.time.Instant

interface LimitChangeRequestRepository {
    fun findById(tenantId: TenantId, id: LimitChangeRequestId): LimitRuleChangeRequest?
    fun findByIdempotencyKey(tenantId: TenantId, key: IdempotencyKey): LimitRuleChangeRequest?
    fun findByLimitRule(tenantId: TenantId, limitRuleId: LimitRuleId): List<LimitRuleChangeRequest>
    fun findPendingForTarget(tenantId: TenantId, targetScopeHash: ScopeHash, at: Instant): List<LimitRuleChangeRequest>
    fun findDueForApplication(tenantId: TenantId, dueAt: Instant, limit: Int): List<LimitRuleChangeRequest>
    fun findByRequesterContext(tenantId: TenantId, requestActorContext: RequestActorContext): List<LimitRuleChangeRequest>
    fun findByRequester(tenantId: TenantId, requester: PartyProfileId): List<LimitRuleChangeRequest>
    fun insert(tenantId: TenantId, request: LimitRuleChangeRequest): LimitRuleChangeRequest
    fun update(tenantId: TenantId, request: LimitRuleChangeRequest): LimitRuleChangeRequest
}
