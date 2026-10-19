package io.github.sepfins.limitguard.port.persistence

import io.github.sepfins.limitguard.domain.change.LimitRuleChangeRequest
import io.github.sepfins.limitguard.domain.change.RequestActorContext
import io.github.sepfins.limitguard.domain.common.IdempotencyKey
import io.github.sepfins.limitguard.domain.common.LimitChangeRequestId
import io.github.sepfins.limitguard.domain.common.PartyProfileId
import io.github.sepfins.limitguard.domain.common.TenantId
import java.time.Instant

interface LimitChangeRequestRepository {
    fun findById(tenantId: TenantId, id: LimitChangeRequestId): LimitRuleChangeRequest?

    fun findByIdForUpdate(tenantId: TenantId, id: LimitChangeRequestId): LimitRuleChangeRequest?

    fun findByIdempotencyKey(tenantId: TenantId, key: IdempotencyKey): LimitRuleChangeRequest?

    /** Discovers the ids of bounded, stably ordered due candidates without acquiring request-row locks. */
    fun findIdsDueForApplication(tenantId: TenantId, dueAt: Instant, limit: Int): List<LimitChangeRequestId>

    /**
     * Claims one discovered row without waiting, rechecking its due state. A row another
     * worker holds is skipped; the caller then locks configuration and rule rows.
     */
    fun claimForApplication(
        tenantId: TenantId,
        id: LimitChangeRequestId,
        dueAt: Instant,
    ): LimitRuleChangeRequest?

    fun findByRequesterContext(tenantId: TenantId, requestActorContext: RequestActorContext): List<LimitRuleChangeRequest>
    fun findByRequester(tenantId: TenantId, requester: PartyProfileId): List<LimitRuleChangeRequest>
    fun insert(tenantId: TenantId, request: LimitRuleChangeRequest): LimitRuleChangeRequest
    fun update(tenantId: TenantId, request: LimitRuleChangeRequest): LimitRuleChangeRequest
}
