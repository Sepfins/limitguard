package io.github.sepfins.limitguard.application.change

import io.github.sepfins.limitguard.api.errors.ConflictException
import io.github.sepfins.limitguard.api.errors.NotFoundException
import io.github.sepfins.limitguard.application.configuration.resolveHierarchy
import io.github.sepfins.limitguard.domain.change.LimitRuleChangeRequest
import io.github.sepfins.limitguard.domain.common.LimitChangeRequestId
import io.github.sepfins.limitguard.domain.common.TenantId
import io.github.sepfins.limitguard.domain.limit.LimitConfiguration
import io.github.sepfins.limitguard.domain.limit.LimitRule
import io.github.sepfins.limitguard.domain.limit.LimitRuleTarget
import io.github.sepfins.limitguard.port.persistence.LimitChangeRequestRepository
import io.github.sepfins.limitguard.port.persistence.LimitConfigurationRepository
import io.github.sepfins.limitguard.port.persistence.LimitRuleRepository

/**
 * A keyed decision or an existing request is locked first. Then, per target in
 * [LimitRuleTarget] order, the configuration row is share-locked so its cap holds, and the
 * target's rule row is locked. A target without a rule has nothing to lock: one pending change
 * per target is a unique index, so a concurrent second request conflicts when it is inserted.
 */
internal class LimitChangeLocks(
    private val configurations: LimitConfigurationRepository,
    private val requests: LimitChangeRequestRepository,
    private val rules: LimitRuleRepository,
) {
    fun acquireTarget(tenantId: TenantId, target: LimitRuleTarget): LockedLimitChangeTarget {
        val configuration = configurations.findByIdForShare(tenantId, target.configurationId)
            ?: throw NotFoundException("limit configuration not found: ${target.configurationId.value}")
        val ancestors = configuration.parentConfigurationId?.let { configurations.resolveHierarchy(tenantId, it) }.orEmpty()
        return LockedLimitChangeTarget(configuration, ancestors, rules.findByTargetForUpdate(tenantId, target))
    }

    /** Re-reads the target's rule under lock, for a caller already holding the target's configuration. */
    fun currentRule(tenantId: TenantId, target: LimitRuleTarget): LimitRule? =
        rules.findByTargetForUpdate(tenantId, target)

    /** Locks the target of each claimed request in target order. */
    fun acquireTargets(tenantId: TenantId, requests: Collection<LimitRuleChangeRequest>): Map<LimitRuleTarget, LockedLimitChangeTarget> =
        requests.map { it.target }.distinct().sorted().associateWith { acquireTarget(tenantId, it) }

    fun acquireRequest(tenantId: TenantId, id: LimitChangeRequestId): LockedLimitChangeRequest {
        val request = requests.findByIdForUpdate(tenantId, id)
            ?: throw NotFoundException("limit change request not found: ${id.value}")
        return LockedLimitChangeRequest(request, acquireTarget(tenantId, request.target))
    }
}

internal data class LockedLimitChangeTarget(
    val configuration: LimitConfiguration,
    /** From the parent to the root; a retired ancestor envelope also stops new grants. */
    val ancestors: List<LimitConfiguration>,
    /** The target's one rule row, live or deleted. */
    val rule: LimitRule?,
)

internal data class LockedLimitChangeRequest(
    val request: LimitRuleChangeRequest,
    val target: LockedLimitChangeTarget,
)

/** The rule a request will change, which must still be the target's rule row. */
internal fun LockedLimitChangeTarget.ruleFor(request: LimitRuleChangeRequest): LimitRule? {
    if (rule?.id != request.limitRuleId) {
        throw ConflictException("limit rule for the target changed since the request: ${request.id.value}")
    }
    return rule
}
