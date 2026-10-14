package io.github.sepfins.limitguard.application.change

import io.github.sepfins.limitguard.api.errors.ConflictException
import io.github.sepfins.limitguard.api.errors.NotFoundException
import io.github.sepfins.limitguard.domain.change.LimitRuleChangeRequest
import io.github.sepfins.limitguard.domain.change.LimitRuleChangeStatus
import io.github.sepfins.limitguard.domain.common.LimitChangeRequestId
import io.github.sepfins.limitguard.domain.limit.LimitRule
import io.github.sepfins.limitguard.domain.limit.LimitRuleTargetScope
import io.github.sepfins.limitguard.internal.hashing.TargetScopeCanonicalizer
import io.github.sepfins.limitguard.port.persistence.LimitChangeRequestRepository
import io.github.sepfins.limitguard.port.persistence.LimitConfigurationRepository
import io.github.sepfins.limitguard.port.persistence.LimitRuleRepository
import java.time.Instant

/** Keeps target identity, conflict checks, and transaction locking in one place. */
internal class LimitChangeTargets(
    private val configurations: LimitConfigurationRepository,
    private val rules: LimitRuleRepository,
    private val requests: LimitChangeRequestRepository,
) {
    fun scopeFor(rule: LimitRule): LimitRuleTargetScope {
        val funding = configurations.findById(rule.tenantId, rule.configurationId)?.fundingScope
            ?: throw NotFoundException("limit configuration not found: ${rule.configurationId.value}")
        return rule.toTargetScope(funding)
    }

    fun requireAvailable(
        candidate: LimitRule,
        requestId: LimitChangeRequestId,
        at: Instant,
        lockedTargets: LockedLimitChangeTargets,
    ): LimitRuleTargetScope {
        val target = checkRules(candidate, lockedTargets)
        val hash = TargetScopeCanonicalizer.hash(target)
        val ruleHasPendingChange = requests.findByLimitRule(candidate.tenantId, candidate.id).any {
            it.id != requestId && it.status in PENDING_STATUSES && (it.expiresAt == null || it.expiresAt > at)
        }
        if (ruleHasPendingChange) {
            throw ConflictException("pending limit change already exists for rule: ${candidate.id.value}")
        }

        val targetHasPendingChange = requests.findPendingForTarget(candidate.tenantId, hash, at).any {
            it.id != requestId
        }
        if (targetHasPendingChange) {
            throw ConflictException("pending limit change already exists for target scope: ${hash.value}")
        }
        return target
    }

    fun requireApplicable(
        request: LimitRuleChangeRequest,
        candidate: LimitRule,
        lockedTargets: LockedLimitChangeTargets,
    ) {
        requireMatches(request, candidate)
        checkRules(candidate, lockedTargets)
    }

    private fun checkRules(candidate: LimitRule, lockedTargets: LockedLimitChangeTargets): LimitRuleTargetScope {
        val target = scopeFor(candidate)
        lockedTargets.requireHeld(TargetScopeCanonicalizer.hash(target))
        val overlaps = candidate.isActive && rules.findByOwner(candidate.tenantId, candidate.ownerPartyProfileId).any {
            it.id != candidate.id && it.isActive && it.effectiveWindow.overlaps(candidate.effectiveWindow) &&
                scopeFor(it) == target
        }
        if (overlaps) {
            throw ConflictException("active limit rule already exists for target scope: ${TargetScopeCanonicalizer.hash(target).value}")
        }
        return target
    }

    fun requireMatches(request: LimitRuleChangeRequest, candidate: LimitRule) {
        if (candidate.tenantId != request.tenantId || candidate.ownerPartyProfileId != request.targetOwnerPartyProfileId ||
            (request.limitRuleId != null && candidate.id != request.limitRuleId)
        ) {
            throw ConflictException("approved rule identity does not match change request: ${request.id.value}")
        }

        val expected = TargetScopeCanonicalizer.snapshot(scopeFor(candidate)).value
        if (expected != request.targetScope.value) {
            throw ConflictException("approved rule scope does not match change request: ${request.id.value}")
        }
    }

    private companion object {
        val PENDING_STATUSES = setOf(
            LimitRuleChangeStatus.REQUESTED,
            LimitRuleChangeStatus.APPROVED,
            LimitRuleChangeStatus.PENDING_EFFECTIVE_DATE,
        )
    }
}
