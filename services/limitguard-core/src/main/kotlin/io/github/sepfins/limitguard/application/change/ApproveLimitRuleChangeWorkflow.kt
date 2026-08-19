package io.github.sepfins.limitguard.application.change

import io.github.sepfins.limitguard.api.commands.ApproveLimitRuleChangeCommand
import io.github.sepfins.limitguard.api.errors.ConflictException
import io.github.sepfins.limitguard.api.errors.NotFoundException
import io.github.sepfins.limitguard.api.errors.ValidationException
import io.github.sepfins.limitguard.api.results.LimitChangeRequestResult
import io.github.sepfins.limitguard.domain.change.LimitRuleChangeRequest
import io.github.sepfins.limitguard.domain.change.LimitRuleChangeStatus
import io.github.sepfins.limitguard.domain.change.LimitRuleChangeType
import io.github.sepfins.limitguard.domain.change.ScopeHash
import io.github.sepfins.limitguard.domain.common.LimitChangeRequestId
import io.github.sepfins.limitguard.domain.common.MandateId
import io.github.sepfins.limitguard.domain.common.Metadata
import io.github.sepfins.limitguard.domain.common.TenantId
import io.github.sepfins.limitguard.domain.limit.LimitRule
import io.github.sepfins.limitguard.domain.mandate.MandateScope
import io.github.sepfins.limitguard.internal.hashing.StableJsonHasher
import io.github.sepfins.limitguard.internal.hashing.TargetScopeCanonicalizer
import io.github.sepfins.limitguard.port.persistence.LimitChangeRequestRepository
import io.github.sepfins.limitguard.port.persistence.LimitConfigurationRepository
import io.github.sepfins.limitguard.port.persistence.LimitRuleChangeEventRepository
import io.github.sepfins.limitguard.port.persistence.LimitRuleRepository
import io.github.sepfins.limitguard.port.time.ClockProvider
import io.github.sepfins.limitguard.port.transactions.TransactionRunner
import java.time.Instant

internal class ApproveLimitRuleChangeWorkflow(
    private val candidateFactory: LimitRuleCandidateFactory,
    private val applicationWorkflow: LimitChangeApplicationWorkflow,
    private val changeEventFactory: LimitChangeEventFactory,
    private val changeRequestRepository: LimitChangeRequestRepository,
    private val changeEventRepository: LimitRuleChangeEventRepository,
    private val limitConfigurationRepository: LimitConfigurationRepository,
    private val limitRuleRepository: LimitRuleRepository,
    private val clockProvider: ClockProvider,
    private val transactionRunner: TransactionRunner,
) {
    fun execute(command: ApproveLimitRuleChangeCommand): LimitChangeRequestResult = mapValidation {
        require(command.decisionReason == null || command.decisionReason.isNotBlank()) {
            "decisionReason must not be blank"
        }
        val tenantId = TenantId.of(command.tenantId)
        val changeRequestId = LimitChangeRequestId.of(command.changeRequestId)
        val actor = command.approvedBy.toChangeActor()
        val now = clockProvider.now()

        transactionRunner.inTransaction {
            val request = changeRequestRepository.findById(tenantId, changeRequestId)
                ?: throw NotFoundException("limit change request not found: ${changeRequestId.value}")
            requireOpenRequest(request, now)

            val currentRule = request.limitRuleId?.let { limitRuleId ->
                limitRuleRepository.findById(tenantId, limitRuleId)
                    ?: throw NotFoundException("limit rule not found: ${limitRuleId.value}")
            }
            requireCurrentRuleVersion(request, currentRule)
            val requestedCandidate = LimitRuleSnapshotter.restoreRule(
                snapshot = request.requestedRule,
                createdAt = currentRule?.createdAt ?: request.createdAt,
                updatedAt = now,
            )
            requireCandidateIdentity(request, requestedCandidate)

            val approvedCandidate = command.approvedRule?.let { approvedRule ->
                require(request.changeType in ADJUSTABLE_CHANGE_TYPES) {
                    "approvedRule is not supported for ${request.changeType} changes"
                }
                candidateFactory.createApprovedCandidate(
                    requestedCandidate = requestedCandidate,
                    approvedRule = approvedRule,
                    effectiveAt = request.effectiveAt,
                    now = now,
                )
            } ?: requestedCandidate
            validateCandidate(request, approvedCandidate, now)

            val targetScope = approvedCandidate.toTargetScope()
            val targetScopeSnapshot = LimitRuleSnapshotter.targetScopeSnapshot(targetScope)
            val targetScopeHash = ScopeHash.of(
                StableJsonHasher.sha256Hex(TargetScopeCanonicalizer.canonicalize(targetScope))
            )
            requireTargetAvailable(request, approvedCandidate, targetScopeHash, now)

            val approved = request.approve(
                approvedRule = LimitRuleSnapshotter.ruleSnapshot(approvedCandidate),
                reason = command.decisionReason,
                at = now,
                approvedTargetScope = targetScopeSnapshot,
                approvedTargetScopeHash = targetScopeHash,
                approvedTargetMandateId = approvedCandidate.targetMandateId(),
            )
            val persistedApproved = changeRequestRepository.update(tenantId, approved)
            changeEventRepository.insert(
                tenantId,
                changeEventFactory.approved(
                    before = request,
                    after = persistedApproved,
                    now = now,
                    actor = actor,
                    eventPayload = Metadata(command.metadata),
                ),
            )

            if (persistedApproved.effectiveAt != null && persistedApproved.effectiveAt > now) {
                val scheduled = persistedApproved.schedule(persistedApproved.effectiveAt, now)
                val persistedScheduled = changeRequestRepository.update(tenantId, scheduled)
                changeEventRepository.insert(
                    tenantId,
                    changeEventFactory.scheduled(persistedApproved, persistedScheduled, now, actor),
                )
                return@inTransaction persistedScheduled.toResult()
            }

            applicationWorkflow.applyInCurrentTransaction(
                request = persistedApproved,
                candidate = approvedCandidate,
                actor = actor,
                applicationTime = now,
            ).toResult()
        }
    }

    private fun requireOpenRequest(request: LimitRuleChangeRequest, now: Instant) {
        if (request.status != LimitRuleChangeStatus.REQUESTED) {
            throw ConflictException("limit change request is not awaiting approval: ${request.id.value}")
        }
        if (request.expiresAt != null && request.expiresAt <= now) {
            throw ConflictException("limit change request has expired: ${request.id.value}")
        }
    }

    private fun requireCandidateIdentity(
        request: LimitRuleChangeRequest,
        candidate: LimitRule,
    ) {
        require(candidate.tenantId == request.tenantId) {
            "requested rule tenant does not match change request"
        }
        require(candidate.ownerPartyProfileId == request.targetOwnerPartyProfileId) {
            "requested rule owner does not match change request"
        }
        require(request.limitRuleId == null || candidate.id == request.limitRuleId) {
            "requested rule id does not match change request"
        }
    }

    private fun requireCurrentRuleVersion(
        request: LimitRuleChangeRequest,
        currentRule: LimitRule?,
    ) {
        if (currentRule == null) return
        val expectedVersion = (request.beforeRule?.value?.get("version") as? Number)?.toInt()
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
            val configuration = limitConfigurationRepository.findById(request.tenantId, configurationId)
                ?: throw NotFoundException("limit configuration not found: ${configurationId.value}")
            require(configuration.isEffectiveAt(candidate.effectiveWindow.startsAt)) {
                "limit configuration is not active at approved rule start: ${configuration.id.value}"
            }
            candidate.validateAgainst(configuration)
        }
    }

    private fun requireTargetAvailable(
        request: LimitRuleChangeRequest,
        candidate: LimitRule,
        targetScopeHash: ScopeHash,
        now: Instant,
    ) {
        if (candidate.isActive) {
            val candidateEffectiveAt = maxOf(
                now,
                request.effectiveAt ?: now,
                candidate.effectiveWindow.startsAt,
            )
            val targetAlreadyExists = limitRuleRepository
                .findActiveForOwner(request.tenantId, candidate.ownerPartyProfileId, candidateEffectiveAt)
                .any { it.id != candidate.id && it.toTargetScope() == candidate.toTargetScope() }
            if (targetAlreadyExists) {
                throw ConflictException("active limit rule already exists for target scope: ${targetScopeHash.value}")
            }
        }

        val conflictingRequest = changeRequestRepository
            .findPendingForTarget(request.tenantId, targetScopeHash, now)
            .any { it.id != request.id }
        if (conflictingRequest) {
            throw ConflictException("pending limit change already exists for target scope: ${targetScopeHash.value}")
        }
    }

    private fun LimitRule.targetMandateId(): MandateId? =
        when (val scope = mandateScope) {
            MandateScope.AllOwnerMandates -> null
            is MandateScope.MandateSpecific -> scope.mandateId
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
