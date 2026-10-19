package io.github.sepfins.limitguard.application.change

import io.github.sepfins.limitguard.api.errors.ConflictException
import io.github.sepfins.limitguard.domain.change.ChangeActor
import io.github.sepfins.limitguard.domain.change.LimitRuleChangeRequest
import io.github.sepfins.limitguard.domain.change.LimitRuleChangeStatus
import io.github.sepfins.limitguard.domain.change.LimitRuleChangeType
import io.github.sepfins.limitguard.domain.change.LimitRuleRevision
import io.github.sepfins.limitguard.domain.change.LimitRuleRevisionEventType
import io.github.sepfins.limitguard.domain.change.RevisionNumber
import io.github.sepfins.limitguard.domain.common.LimitChangePolicyId
import io.github.sepfins.limitguard.domain.common.LimitRuleId
import io.github.sepfins.limitguard.domain.common.Metadata
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
    private val candidateFactory: LimitRuleCandidateFactory,
    private val mandateValidator: LimitRuleMandateValidator,
    private val eligibility: LimitChangeEligibilityValidator,
    private val idGenerator: IdGenerator,
) {
    /** Checks the rule [request] would produce when it applies, against the locked target. */
    fun validate(request: LimitRuleChangeRequest, target: LockedLimitChangeTarget, at: Instant): LimitRule {
        val applicationTime = request.effectiveAt?.takeIf { it > at } ?: at
        val candidate = candidateFactory.candidate(request, target, { LimitRuleId.of(idGenerator.newId()) }, applicationTime)
        mandateValidator.validate(candidate, applicationTime)
        eligibility.validateApprovedIntent(request, candidate, at)
        return candidate
    }

    fun approveAndApply(
        request: LimitRuleChangeRequest,
        target: LockedLimitChangeTarget,
        actor: ChangeActor,
        reason: String?,
        at: Instant,
        eventPayload: Metadata = Metadata.EMPTY,
        policyId: LimitChangePolicyId? = null,
    ): LimitRuleChangeRequest {
        validate(request, target, at)
        val approved = changeRequestRepository.update(request.tenantId, request.approve(reason, at))
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
        return applyInCurrentTransaction(approved, target, actor, at)
    }

    fun applyInCurrentTransaction(
        request: LimitRuleChangeRequest,
        target: LockedLimitChangeTarget,
        actor: ChangeActor,
        applicationTime: Instant,
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
        val currentRule = target.ruleFor(request)
        val candidate = validate(request, target, applicationTime)

        val persistedRule = if (currentRule == null) {
            limitRuleRepository.insert(request.tenantId, candidate)
        } else {
            limitRuleRepository.updateIfVersion(request.tenantId, candidate, currentRule.version)
                ?: throw ConflictException("limit rule version conflict: expected ${currentRule.version.value}")
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
                beforeAmount = request.beforeAmount,
                afterAmount = persistedRule.amount,
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
