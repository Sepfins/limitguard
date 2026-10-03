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

    fun findByIdForUpdate(tenantId: TenantId, id: LimitChangeRequestId): LimitRuleChangeRequest?

    fun findByIdempotencyKey(tenantId: TenantId, key: IdempotencyKey): LimitRuleChangeRequest?
    fun findByLimitRule(tenantId: TenantId, limitRuleId: LimitRuleId): List<LimitRuleChangeRequest>
    fun findPendingForTarget(tenantId: TenantId, targetScopeHash: ScopeHash, at: Instant): List<LimitRuleChangeRequest>

    /**
     * Locks the logical target for the surrounding transaction, including when
     * neither a request nor a rule exists yet. Implementations must serialize
     * callers for the same tenant and funding-aware target hash; locking only
     * existing rows is insufficient for concurrent CREATE requests.
     */
    fun lockTarget(tenantId: TenantId, targetScopeHash: ScopeHash)

    /** Nonblocking logical target acquisition for batch workers; false means skip this work. */
    fun tryLockTarget(tenantId: TenantId, targetScopeHash: ScopeHash): Boolean

    /** Discovers bounded, stably ordered due candidates without acquiring request-row locks. */
    fun findDueForApplication(tenantId: TenantId, dueAt: Instant, limit: Int): List<LimitRuleChangeRequest>

    /**
     * Claims one discovered row without waiting, rechecking its due state. The caller
     * must acquire all of its logical targets before this claim, then lock rule rows.
     */
    fun claimForApplication(
        tenantId: TenantId,
        id: LimitChangeRequestId,
        dueAt: Instant,
    ): LimitRuleChangeRequest?

    /**
     * Claims due request rows for the surrounding transaction. Database
     * implementations must use non-blocking row claims (for example,
     * FOR UPDATE SKIP LOCKED) so concurrent schedulers receive disjoint work.
     * The returned requests must be processed before that transaction ends.
     * Callers must already hold every affected logical target. Core workflows use
     * discovery and individual claims instead so they can gather the complete lock set.
     */
    fun claimDueForApplication(
        tenantId: TenantId,
        dueAt: Instant,
        limit: Int,
    ): List<LimitRuleChangeRequest>

    fun findByRequesterContext(tenantId: TenantId, requestActorContext: RequestActorContext): List<LimitRuleChangeRequest>
    fun findByRequester(tenantId: TenantId, requester: PartyProfileId): List<LimitRuleChangeRequest>
    fun insert(tenantId: TenantId, request: LimitRuleChangeRequest): LimitRuleChangeRequest
    fun update(tenantId: TenantId, request: LimitRuleChangeRequest): LimitRuleChangeRequest
}
