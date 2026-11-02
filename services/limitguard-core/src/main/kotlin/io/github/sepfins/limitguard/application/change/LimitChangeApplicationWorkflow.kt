package io.github.sepfins.limitguard.application.change

import io.github.sepfins.limitguard.api.errors.ConflictException
import io.github.sepfins.limitguard.api.errors.LimitguardException
import io.github.sepfins.limitguard.api.errors.NotFoundException
import io.github.sepfins.limitguard.api.errors.ValidationException
import io.github.sepfins.limitguard.domain.change.ChangeActor
import io.github.sepfins.limitguard.domain.change.LimitRuleChangeRequest
import io.github.sepfins.limitguard.domain.change.LimitRuleChangeType
import io.github.sepfins.limitguard.domain.change.LimitRuleRevision
import io.github.sepfins.limitguard.domain.change.LimitRuleRevisionEventType
import io.github.sepfins.limitguard.domain.change.RevisionNumber
import io.github.sepfins.limitguard.domain.common.LimitChangePolicyId
import io.github.sepfins.limitguard.domain.common.LimitRuleId
import io.github.sepfins.limitguard.domain.common.LimitRuleRevisionId
import io.github.sepfins.limitguard.domain.common.Metadata
import io.github.sepfins.limitguard.domain.limit.LimitRule
import io.github.sepfins.limitguard.port.ids.IdGenerator
import io.github.sepfins.limitguard.port.persistence.LimitRuleRepository
import io.github.sepfins.limitguard.port.persistence.LimitRuleRevisionRepository
import io.github.sepfins.limitguard.port.transactions.TransactionRunner
import java.time.Instant

/**
 * Takes a locked limit change request to its next state: Change Approval, which adopts the
 * change now or schedules it, and Change Application of a due change. Each call validates the
 * rule the request produces against the locked target once, at the change's application time.
 */
internal class LimitChangeApplicationWorkflow(
    private val transitions: LimitChangeTransitionRecorder,
    private val limitRuleRepository: LimitRuleRepository,
    private val limitRuleRevisionRepository: LimitRuleRevisionRepository,
    private val candidateFactory: LimitRuleCandidateFactory,
    private val eligibility: LimitChangeEligibilityValidator,
    private val idGenerator: IdGenerator,
    private val transactionRunner: TransactionRunner,
) {
    /**
     * Accepts [request] as submitted and adopts it in this transaction, or schedules it while
     * its effective time is still ahead. A request that is not awaiting approval or whose
     * deadline has passed conflicts; a rule its target no longer admits is a validation error.
     * [alreadyValidated] skips that check for a request validated in this transaction at [at].
     */
    fun approve(
        request: LimitRuleChangeRequest,
        target: LockedLimitChangeTarget,
        actor: ChangeActor,
        reason: String?,
        at: Instant,
        eventPayload: Metadata = Metadata.EMPTY,
        policyId: LimitChangePolicyId? = null,
        alreadyValidated: Boolean = false,
    ): LimitRuleChangeRequest {
        if (!request.isAwaitingApproval) {
            throw ConflictException("limit change request is not awaiting approval: ${request.id.value}")
        }
        if (request.isElapsedAt(at)) {
            throw ConflictException("${LimitRuleChangeRequest.DEADLINE_ELAPSED}: ${request.id.value}")
        }
        val candidate = if (alreadyValidated) candidate(request, target, at) else validate(request, target, at)
        val approved = transitions.approve(request, reason, actor, at, eventPayload, policyId)
        if (!approved.isDueAt(at)) {
            return transitions.schedule(approved, approved.applicationTimeFrom(at), actor, at)
        }
        return adopt(approved, target, candidate, actor, at)
    }

    /**
     * Adopts the due [request] as its target's rule state. An elapsed request, or one its
     * target no longer admits, is recorded as a Change Failure instead, after rolling the item
     * back to its own savepoint so the outer transaction continues. Faults propagate.
     */
    fun apply(request: LimitRuleChangeRequest, target: LockedLimitChangeTarget, at: Instant): LimitChangeApplicationOutcome {
        if (request.isElapsedAt(at)) {
            return LimitChangeApplicationOutcome.Failed(transitions.fail(request, LimitRuleChangeRequest.DEADLINE_ELAPSED, at))
        }
        check(request.isDueAt(at)) { "limit change request is not due for application: ${request.id.value}" }
        return try {
            val applied = transactionRunner.inSavepoint {
                adopt(request, target, validate(request, target, at), ChangeActor.System, at)
            }
            LimitChangeApplicationOutcome.Applied(applied)
        } catch (exception: LimitguardException) {
            val reason = exception.changeFailureReason() ?: throw exception
            LimitChangeApplicationOutcome.Failed(transitions.fail(request, reason, at))
        }
    }

    /** Rechecks a pending [request] a policy amended: the rule it produces must still fit at its new application time. */
    fun recheck(request: LimitRuleChangeRequest, target: LockedLimitChangeTarget, at: Instant) {
        validate(request, target, at)
    }

    private fun validate(request: LimitRuleChangeRequest, target: LockedLimitChangeTarget, at: Instant): LimitRule {
        val candidate = candidate(request, target, at)
        eligibility.validateApprovedIntent(request, candidate, at, request.applicationTimeFrom(at))
        return candidate
    }

    private fun candidate(request: LimitRuleChangeRequest, target: LockedLimitChangeTarget, at: Instant): LimitRule =
        try {
            candidateFactory.candidate(request, target, { LimitRuleId.of(idGenerator.newId()) }, request.applicationTimeFrom(at))
        } catch (exception: IllegalArgumentException) {
            throw ValidationException(
                message = exception.message ?: "limit change does not fit its configuration",
                cause = exception,
            )
        }

    private fun adopt(
        request: LimitRuleChangeRequest,
        target: LockedLimitChangeTarget,
        candidate: LimitRule,
        actor: ChangeActor,
        at: Instant,
    ): LimitRuleChangeRequest {
        val currentRule = target.ruleFor(request)
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
                effectiveAt = at,
                createdAt = at,
            ),
        )

        return transitions.apply(request, persistedRule.id, actor, at)
    }

    /** The business reasons a due change cannot be adopted; anything else is a fault to surface. */
    private fun LimitguardException.changeFailureReason(): String? =
        when (this) {
            is ConflictException, is NotFoundException, is ValidationException ->
                message ?: "scheduled limit change application failed"
            else -> null
        }

    private fun LimitRuleChangeType.toRevisionEventType(): LimitRuleRevisionEventType =
        when (this) {
            LimitRuleChangeType.CREATE -> LimitRuleRevisionEventType.CREATED
            LimitRuleChangeType.UPDATE -> LimitRuleRevisionEventType.UPDATED
            LimitRuleChangeType.DEACTIVATE -> LimitRuleRevisionEventType.DEACTIVATED
            LimitRuleChangeType.REACTIVATE -> LimitRuleRevisionEventType.REACTIVATED
            LimitRuleChangeType.DELETE -> LimitRuleRevisionEventType.DELETED
        }
}
