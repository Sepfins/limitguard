package io.github.sepfins.limitguard.application.change

import io.github.sepfins.limitguard.api.commands.ApproveLimitRuleChangeCommand
import io.github.sepfins.limitguard.api.TrustedChangeActorContext
import io.github.sepfins.limitguard.api.errors.ConflictException
import io.github.sepfins.limitguard.api.errors.NotFoundException
import io.github.sepfins.limitguard.api.errors.ValidationException
import io.github.sepfins.limitguard.api.results.LimitChangeRequestResult
import io.github.sepfins.limitguard.application.configuration.resolveHierarchy
import io.github.sepfins.limitguard.domain.change.LimitRuleChangeRequest
import io.github.sepfins.limitguard.domain.change.LimitRuleChangeStatus
import io.github.sepfins.limitguard.domain.change.LimitRuleChangeType
import io.github.sepfins.limitguard.domain.common.LimitChangeRequestId
import io.github.sepfins.limitguard.domain.common.Metadata
import io.github.sepfins.limitguard.domain.common.TenantId
import io.github.sepfins.limitguard.domain.limit.LimitRule
import io.github.sepfins.limitguard.port.persistence.LimitChangeRequestRepository
import io.github.sepfins.limitguard.port.persistence.LimitConfigurationRepository
import io.github.sepfins.limitguard.port.persistence.LimitRuleRepository
import io.github.sepfins.limitguard.port.time.ClockProvider
import io.github.sepfins.limitguard.port.transactions.TransactionRunner
import java.time.Instant

internal class ApproveLimitRuleChangeWorkflow(
    private val eligibility: LimitChangeEligibilityValidator,
    private val candidateFactory: LimitRuleCandidateFactory,
    private val mandateValidator: LimitRuleMandateValidator,
    private val applicationWorkflow: LimitChangeApplicationWorkflow,
    private val targets: LimitChangeTargets,
    private val locks: LimitChangeLocks,
    private val changeRequestRepository: LimitChangeRequestRepository,
    private val limitConfigurationRepository: LimitConfigurationRepository,
    private val limitRuleRepository: LimitRuleRepository,
    private val clockProvider: ClockProvider,
    private val transactionRunner: TransactionRunner,
) {
    fun execute(command: ApproveLimitRuleChangeCommand, trustedActorContext: TrustedChangeActorContext?): LimitChangeRequestResult = mapValidation {
        command.approvedBy.requireTrustedAuthority(trustedActorContext)
        require(command.decisionReason == null || command.decisionReason.isNotBlank()) {
            "decisionReason must not be blank"
        }
        val tenantId = TenantId.of(command.tenantId)
        val changeRequestId = LimitChangeRequestId.of(command.changeRequestId)
        val actor = command.approvedBy.toChangeActor()
        val now = clockProvider.now()

        transactionRunner.inTransaction {
            val locked = locks.acquireRequest(tenantId, changeRequestId) { observed ->
                requireOpenRequest(observed, now)
                val requested = LimitRuleSnapshotter.restoreRule(observed.requestedRule)
                listOf(approvedCandidate(observed, requested, command, now))
            }
            val request = locked.request
            requireOpenRequest(request, now)
            eligibility.validateActionActor(tenantId, actor)

            val currentRule = locked.currentRule
            if (request.limitRuleId != null && currentRule == null) {
                throw NotFoundException("limit rule not found: ${request.limitRuleId.value}")
            }
            requireCurrentRuleVersion(request, currentRule)
            val requestedCandidate = LimitRuleSnapshotter.restoreRule(
                snapshot = request.requestedRule,
                createdAt = currentRule?.createdAt ?: request.createdAt,
                updatedAt = now,
            )
            targets.requireMatches(request, requestedCandidate)

            val approvedCandidate = approvedCandidate(request, requestedCandidate, command, now)
            validateCandidate(request, approvedCandidate, now)
            mandateValidator.validate(approvedCandidate, request.effectiveAt ?: now)

            applicationWorkflow.approveAndApply(
                request = request,
                candidate = approvedCandidate,
                actor = actor,
                reason = command.decisionReason,
                at = now,
                eventPayload = Metadata(command.metadata),
                lockedTargets = locked.targets,
            ).toResult()
        }
    }

    private fun approvedCandidate(
        request: LimitRuleChangeRequest,
        requestedCandidate: LimitRule,
        command: ApproveLimitRuleChangeCommand,
        now: Instant,
    ): LimitRule = command.approvedRule?.let { approvedRule ->
        require(request.changeType in ADJUSTABLE_CHANGE_TYPES) {
            "approvedRule is not supported for ${request.changeType} changes"
        }
        candidateFactory.createApprovedCandidate(requestedCandidate, approvedRule, request.effectiveAt, now)
    } ?: requestedCandidate

    private fun requireOpenRequest(request: LimitRuleChangeRequest, now: Instant) {
        if (request.status != LimitRuleChangeStatus.REQUESTED) {
            throw ConflictException("limit change request is not awaiting approval: ${request.id.value}")
        }
        if (request.expiresAt != null && request.expiresAt <= now) {
            throw ConflictException("limit change request has expired: ${request.id.value}")
        }
    }

    private fun requireCurrentRuleVersion(
        request: LimitRuleChangeRequest,
        currentRule: LimitRule?,
    ) {
        if (currentRule == null) return
        val expectedVersion = request.beforeRule?.let { LimitRuleSnapshotter.version(it).value }
            ?: throw ConflictException("limit change request is missing its expected rule version")
        if (currentRule.version.value != expectedVersion) {
            throw ConflictException(
                "limit rule version conflict: expected $expectedVersion, found ${currentRule.version.value}"
            )
        }
    }

    private fun validateCandidate(
        request: LimitRuleChangeRequest,
        candidate: LimitRule,
        now: Instant,
    ) {
        if (candidate.isActive) {
            val applicationTime = request.effectiveAt ?: now
            require(candidate.effectiveWindow.endsAt == null || candidate.effectiveWindow.endsAt > applicationTime) {
                "active limit rule must not end before it is applied"
            }
        }
        val configurationId = candidate.configurationId
        if (request.changeType == LimitRuleChangeType.CREATE) {
            requireNotNull(configurationId) { "approvedRule.configurationId is required" }
        }
        if (configurationId != null && request.changeType in CONFIGURATION_VALIDATED_CHANGE_TYPES) {
            val hierarchy = limitConfigurationRepository.resolveHierarchy(request.tenantId, configurationId)
            candidate.validateAgainst(hierarchy.first(), hierarchy.drop(1))
        }
    }

    private inline fun <T> mapValidation(block: () -> T): T =
        try {
            block()
        } catch (exception: IllegalArgumentException) {
            throw ValidationException(
                message = exception.message ?: "limit change approval validation failed",
                cause = exception,
            )
        }

    private companion object {
        val ADJUSTABLE_CHANGE_TYPES = setOf(
            LimitRuleChangeType.CREATE,
            LimitRuleChangeType.UPDATE,
            LimitRuleChangeType.REACTIVATE,
        )
        val CONFIGURATION_VALIDATED_CHANGE_TYPES = ADJUSTABLE_CHANGE_TYPES
    }
}
