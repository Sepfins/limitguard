package io.github.sepfins.limitguard.application.change

import io.github.sepfins.limitguard.api.errors.ConflictException
import io.github.sepfins.limitguard.api.errors.NotFoundException
import io.github.sepfins.limitguard.domain.change.ChangeActor
import io.github.sepfins.limitguard.domain.change.LimitRuleChangeRequest
import io.github.sepfins.limitguard.domain.change.LimitRuleChangeStatus
import io.github.sepfins.limitguard.domain.change.LimitRuleChangeType
import io.github.sepfins.limitguard.domain.change.LimitRuleRevision
import io.github.sepfins.limitguard.domain.change.LimitRuleRevisionEventType
import io.github.sepfins.limitguard.domain.change.RevisionNumber
import io.github.sepfins.limitguard.domain.common.LimitChangePolicyId
import io.github.sepfins.limitguard.domain.common.Metadata
import io.github.sepfins.limitguard.domain.mandate.MandateScope
import io.github.sepfins.limitguard.internal.hashing.TargetScopeCanonicalizer
import io.github.sepfins.limitguard.domain.common.LimitRuleRevisionId
import io.github.sepfins.limitguard.domain.limit.LimitRule
import io.github.sepfins.limitguard.port.ids.IdGenerator
import io.github.sepfins.limitguard.port.persistence.LimitChangeRequestRepository
import io.github.sepfins.limitguard.port.persistence.LimitRuleChangeEventRepository
import io.github.sepfins.limitguard.port.persistence.LimitRuleRepository
import io.github.sepfins.limitguard.port.persistence.LimitRuleRevisionRepository
import java.time.Instant

internal class LimitChangeApplicationWorkflow(
    private val changeRequestRepository: LimitChangeRequestRepository,
    private val changeEventRepository: LimitRuleChangeEventRepository,
    private val limitRuleRepository: LimitRuleRepository,
    private val limitRuleRevisionRepository: LimitRuleRevisionRepository,
    private val changeEventFactory: LimitChangeEventFactory,
    private val targets: LimitChangeTargets,
    private val idGenerator: IdGenerator,
) {
    fun approveAndApply(
        request: LimitRuleChangeRequest,
        candidate: LimitRule,
        actor: ChangeActor,
        reason: String?,
        at: Instant,
        lockedTargets: LockedLimitChangeTargets,
        eventPayload: Metadata = Metadata.EMPTY,
        policyId: LimitChangePolicyId? = null,
    ): LimitRuleChangeRequest {
        val target = targets.requireAvailable(candidate, request.id, at, lockedTargets)
        val approved = changeRequestRepository.update(request.tenantId, request.approve(
            approvedRule = LimitRuleSnapshotter.ruleSnapshot(candidate),
            reason = reason,
            at = at,
            approvedTargetScope = TargetScopeCanonicalizer.snapshot(target),
            approvedTargetScopeHash = TargetScopeCanonicalizer.hash(target),
            approvedTargetMandateId = (candidate.mandateScope as? MandateScope.MandateSpecific)?.mandateId,
        ))
        changeEventRepository.insert(request.tenantId, changeEventFactory.approved(
            before = request, after = approved, now = at, actor = actor,
            eventPayload = eventPayload, policyId = policyId,
        ))

        val effectiveAt = approved.effectiveAt
        if (effectiveAt != null && effectiveAt > at) {
            val scheduled = changeRequestRepository.update(request.tenantId, approved.schedule(effectiveAt, at))
            changeEventRepository.insert(request.tenantId, changeEventFactory.scheduled(approved, scheduled, at, actor))
            return scheduled
        }
        return applyInCurrentTransaction(approved, candidate, actor, at, lockedTargets)
    }

    fun applyInCurrentTransaction(
        request: LimitRuleChangeRequest,
        candidate: LimitRule,
        actor: ChangeActor,
        applicationTime: Instant,
        lockedTargets: LockedLimitChangeTargets,
    ): LimitRuleChangeRequest {
        if (request.status !in APPLICABLE_STATUSES) {
            throw ConflictException("limit change request is not approved: ${request.id.value}")
        }
        if (request.effectiveAt != null && request.effectiveAt > applicationTime) {
            throw ConflictException("limit change request is not due: ${request.id.value}")
        }
        if (request.expiresAt != null && request.expiresAt <= applicationTime) {
            throw ConflictException("limit change request has expired: ${request.id.value}")
        }
        targets.requireApplicable(request, candidate, lockedTargets)

        val candidateAtApplication = candidate.copy(updatedAt = applicationTime)
        val persistedRule = when (request.changeType) {
            LimitRuleChangeType.CREATE -> limitRuleRepository.insert(request.tenantId, candidateAtApplication)
            else -> {
                val currentRule = limitRuleRepository.findByIdForUpdate(
                    request.tenantId,
                    requireNotNull(request.limitRuleId),
                ) ?: throw NotFoundException("limit rule not found: ${request.limitRuleId.value}")
                val expectedVersion = request.beforeRule?.let { LimitRuleSnapshotter.version(it).value }
                    ?: throw ConflictException("limit change request is missing its expected rule version")
                if (currentRule.version.value != expectedVersion) {
                    throw ConflictException(
                        "limit rule version conflict: expected $expectedVersion, found ${currentRule.version.value}"
                    )
                }
                if (candidateAtApplication.version.value != currentRule.version.next().value) {
                    throw ConflictException("approved rule has an invalid version: ${candidateAtApplication.version.value}")
                }
                limitRuleRepository.updateIfVersion(
                    tenantId = request.tenantId,
                    limitRule = candidateAtApplication,
                    expectedVersion = currentRule.version,
                ) ?: run {
                    val actualVersion = limitRuleRepository.findById(request.tenantId, currentRule.id)?.version?.value
                    throw ConflictException(
                        "limit rule version conflict: expected $expectedVersion, found ${actualVersion ?: "missing"}"
                    )
                }
            }
        }

        limitRuleRevisionRepository.insert(
            request.tenantId,
            LimitRuleRevision(
                id = LimitRuleRevisionId.of(idGenerator.newId()),
                tenantId = request.tenantId,
                limitRuleId = persistedRule.id,
                revisionNumber = RevisionNumber.of(persistedRule.version.value),
                revisionEventType = request.changeType.toRevisionEventType(),
                actor = actor,
                changeRequestId = request.id,
                beforeRule = request.beforeRule,
                afterRule = LimitRuleSnapshotter.ruleSnapshot(persistedRule),
                effectiveAt = applicationTime,
                createdAt = applicationTime,
            ),
        )

        val applied = request.markApplied(persistedRule.id, applicationTime)
        val persistedApplied = changeRequestRepository.update(request.tenantId, applied)
        changeEventRepository.insert(
            request.tenantId,
            changeEventFactory.applied(request, persistedApplied, applicationTime, actor),
        )
        return persistedApplied
    }

    private fun LimitRuleChangeType.toRevisionEventType(): LimitRuleRevisionEventType =
        when (this) {
            LimitRuleChangeType.CREATE -> LimitRuleRevisionEventType.CREATED
            LimitRuleChangeType.UPDATE -> LimitRuleRevisionEventType.UPDATED
            LimitRuleChangeType.DEACTIVATE -> LimitRuleRevisionEventType.DEACTIVATED
            LimitRuleChangeType.REACTIVATE -> LimitRuleRevisionEventType.REACTIVATED
            LimitRuleChangeType.DELETE -> LimitRuleRevisionEventType.DELETED
        }

    private companion object {
        val APPLICABLE_STATUSES = setOf(
            LimitRuleChangeStatus.APPROVED,
            LimitRuleChangeStatus.PENDING_EFFECTIVE_DATE,
        )
    }
}
