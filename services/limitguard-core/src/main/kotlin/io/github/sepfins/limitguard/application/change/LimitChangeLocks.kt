package io.github.sepfins.limitguard.application.change

import io.github.sepfins.limitguard.api.errors.ConflictException
import io.github.sepfins.limitguard.api.errors.NotFoundException
import io.github.sepfins.limitguard.domain.change.LimitRuleChangeRequest
import io.github.sepfins.limitguard.domain.change.ScopeHash
import io.github.sepfins.limitguard.domain.common.LimitChangeRequestId
import io.github.sepfins.limitguard.domain.common.LimitRuleId
import io.github.sepfins.limitguard.domain.common.TenantId
import io.github.sepfins.limitguard.domain.limit.LimitRule
import io.github.sepfins.limitguard.internal.hashing.TargetScopeCanonicalizer
import io.github.sepfins.limitguard.port.persistence.LimitChangeRequestRepository
import io.github.sepfins.limitguard.port.persistence.LimitRuleRepository

/**
 * A keyed decision acquires its tenant/key lock before entering this protocol.
 * Target hashes, then request IDs, then rule IDs, each sorted.
 * Affected catalog/authority rows come last, ordered by table, tenant, and ID.
 */
internal class LimitChangeLocks(
    private val targets: LimitChangeTargets,
    private val requests: LimitChangeRequestRepository,
    private val rules: LimitRuleRepository,
) {
    fun discover(request: LimitRuleChangeRequest): LimitChangeLockPlan {
        val current = request.limitRuleId?.let { rules.findById(request.tenantId, it) }
        // An unresolved current configuration cannot supply a target; application will fail under the row locks.
        val currentHash = current?.let {
            try { hash(it) } catch (_: NotFoundException) { null }
        }
        return LimitChangeLockPlan(
            targetHashes = setOfNotNull(
                request.targetScopeHash,
                TargetScopeCanonicalizer.hash(request.targetScope),
                currentHash,
            ),
            ruleIds = setOfNotNull(request.limitRuleId),
        )
    }

    fun acquireTargets(tenantId: TenantId, plans: Collection<LimitChangeLockPlan>, skipBusy: Boolean = false): LockedLimitChangeTargets {
        val acquired = plans.flatMap { it.targetHashes }.distinct().sortedBy { it.value }.filter { hash ->
            if (skipBusy) requests.tryLockTarget(tenantId, hash) else {
                requests.lockTarget(tenantId, hash)
                true
            }
        }.toSet()
        return LockedLimitChangeTargets(acquired)
    }

    fun acquireRules(tenantId: TenantId, plans: Collection<LimitChangeLockPlan>): Map<LimitRuleId, LimitRule?> =
        plans.flatMap { it.ruleIds }.distinct().sortedBy { it.value.toString() }
            .associateWith { rules.findByIdForUpdate(tenantId, it) }

    fun acquireRequest(tenantId: TenantId, id: LimitChangeRequestId): LockedLimitChangeRequest {
        val observed = requests.findById(tenantId, id)
            ?: throw NotFoundException("limit change request not found: ${id.value}")
        val plan = discover(observed)
        val held = acquireTargets(tenantId, listOf(plan))
        val request = requests.findByIdForUpdate(tenantId, id)
            ?: throw NotFoundException("limit change request not found: ${id.value}")
        requireSameIdentity(observed, request)
        val current = request.limitRuleId?.let { acquireRules(tenantId, listOf(plan))[it] }
        return LockedLimitChangeRequest(request, current, held)
    }

    fun requireSameIdentity(observed: LimitRuleChangeRequest, locked: LimitRuleChangeRequest) {
        if (observed.targetScopeHash != locked.targetScopeHash || observed.limitRuleId != locked.limitRuleId) {
            throw ConflictException("limit change locking identity changed; retry request: ${locked.id.value}")
        }
    }

    private fun hash(rule: LimitRule): ScopeHash = TargetScopeCanonicalizer.hash(targets.scopeFor(rule))
}

internal data class LimitChangeLockPlan(val targetHashes: Set<ScopeHash>, val ruleIds: Set<LimitRuleId>)

internal class LockedLimitChangeTargets(private val hashes: Set<ScopeHash>) {
    fun covers(plan: LimitChangeLockPlan): Boolean = hashes.containsAll(plan.targetHashes)

    fun requireHeld(hash: ScopeHash) {
        if (hash !in hashes) throw ConflictException("limit change target changed during lock acquisition; retry")
    }
}

internal data class LockedLimitChangeRequest(
    val request: LimitRuleChangeRequest,
    val currentRule: LimitRule?,
    val targets: LockedLimitChangeTargets,
)
