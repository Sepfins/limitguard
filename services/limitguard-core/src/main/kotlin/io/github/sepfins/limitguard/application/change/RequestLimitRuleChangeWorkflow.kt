package io.github.sepfins.limitguard.application.change

import io.github.sepfins.limitguard.api.LimitRuleChangeType
import io.github.sepfins.limitguard.api.commands.LimitRuleDraft
import io.github.sepfins.limitguard.api.commands.RequestLimitRuleChangeCommand
import io.github.sepfins.limitguard.api.errors.ConflictException
import io.github.sepfins.limitguard.api.errors.IdempotencyConflictException
import io.github.sepfins.limitguard.api.errors.ValidationException
import io.github.sepfins.limitguard.api.results.LimitChangeRequestResult
import io.github.sepfins.limitguard.domain.change.ChangeActor
import io.github.sepfins.limitguard.domain.change.LimitRuleChangeRequest
import io.github.sepfins.limitguard.domain.change.LimitRuleChangeStatus
import io.github.sepfins.limitguard.domain.common.CurrencyCode
import io.github.sepfins.limitguard.domain.common.IdempotencyKey
import io.github.sepfins.limitguard.domain.common.LimitChangeRequestId
import io.github.sepfins.limitguard.domain.common.LimitRuleId
import io.github.sepfins.limitguard.domain.common.Money
import io.github.sepfins.limitguard.domain.common.PartyProfileDelegationId
import io.github.sepfins.limitguard.domain.common.PartyProfileId
import io.github.sepfins.limitguard.domain.common.TenantId
import io.github.sepfins.limitguard.domain.limit.LimitRule
import io.github.sepfins.limitguard.domain.limit.LimitRuleName
import io.github.sepfins.limitguard.port.ids.IdGenerator
import io.github.sepfins.limitguard.port.persistence.LimitChangeRequestRepository
import io.github.sepfins.limitguard.port.persistence.LimitRuleChangeEventRepository
import io.github.sepfins.limitguard.port.persistence.LimitRuleRepository
import io.github.sepfins.limitguard.port.time.ClockProvider
import io.github.sepfins.limitguard.port.transactions.TransactionRunner
import java.time.Instant

internal class RequestLimitRuleChangeWorkflow(
    private val commandValidator: ChangeCommandValidator,
    private val referenceResolver: ChangeReferenceResolver,
    private val candidateFactory: LimitRuleCandidateFactory,
    private val changeRequestFactory: LimitChangeRequestFactory,
    private val changeEventFactory: LimitChangeEventFactory,
    private val applicationWorkflow: LimitChangeApplicationWorkflow,
    private val changeRequestRepository: LimitChangeRequestRepository,
    private val changeEventRepository: LimitRuleChangeEventRepository,
    private val limitRuleRepository: LimitRuleRepository,
    private val idGenerator: IdGenerator,
    private val clockProvider: ClockProvider,
    private val transactionRunner: TransactionRunner,
) {
    fun execute(command: RequestLimitRuleChangeCommand): LimitChangeRequestResult = mapValidation {
        val now = clockProvider.now()
        commandValidator.validate(command, now)
        val tenantId = TenantId.of(command.tenantId)

        transactionRunner.inTransaction {
            val existingRequest = command.idempotencyKey
                ?.let { IdempotencyKey.of(it) }
                ?.let { changeRequestRepository.findByIdempotencyKey(tenantId, it) }
            if (existingRequest != null) {
                requireSameIdempotentRequest(existingRequest, command)
                return@inTransaction existingRequest.toResult()
            }

            val facts = referenceResolver.resolve(command)
            val candidate = candidateFactory.createCandidate(
                command = command,
                facts = facts,
                futureRuleId = if (command.changeType == LimitRuleChangeType.CREATE) {
                    LimitRuleId.of(idGenerator.newId())
                } else {
                    null
                },
                now = now,
            )
            val request = changeRequestFactory.create(
                id = LimitChangeRequestId.of(idGenerator.newId()),
                command = command,
                requestedRule = candidate,
                currentRule = facts.currentRule,
                now = now,
            )

            requireTargetAvailable(tenantId, candidate, request, now)

            val requested = changeRequestRepository.insert(tenantId, request)
            changeEventRepository.insert(tenantId, changeEventFactory.requested(requested, now))

            val approved = requested.approve(
                approvedRule = requested.requestedRule,
                reason = "Automatically approved",
                at = now,
            )
            val persistedApproved = changeRequestRepository.update(tenantId, approved)
            changeEventRepository.insert(
                tenantId,
                changeEventFactory.approved(requested, persistedApproved, now),
            )

            if (command.effectiveAt != null && command.effectiveAt > now) {
                val scheduled = persistedApproved.schedule(command.effectiveAt, now)
                val persistedScheduled = changeRequestRepository.update(tenantId, scheduled)
                changeEventRepository.insert(
                    tenantId,
                    changeEventFactory.scheduled(persistedApproved, persistedScheduled, now),
                )
                return@inTransaction persistedScheduled.toResult()
            }

            applicationWorkflow.applyInCurrentTransaction(
                request = persistedApproved,
                candidate = candidate,
                actor = ChangeActor.System,
                applicationTime = now,
            ).toResult()
        }
    }

    private fun requireTargetAvailable(
        tenantId: TenantId,
        candidate: LimitRule,
        request: LimitRuleChangeRequest,
        now: Instant,
    ) {
        request.limitRuleId?.let { limitRuleId ->
            val pendingForRule = changeRequestRepository.findByLimitRule(tenantId, limitRuleId)
                .any {
                    it.status in PENDING_STATUSES &&
                        (it.expiresAt == null || it.expiresAt > now)
                }
            if (pendingForRule) {
                throw ConflictException("pending limit change already exists for rule: ${limitRuleId.value}")
            }
        }

        if (candidate.isActive) {
            val candidateEffectiveAt = maxOf(
                now,
                request.effectiveAt ?: now,
                candidate.effectiveWindow.startsAt,
            )
            val targetAlreadyExists = limitRuleRepository
                .findActiveForOwner(tenantId, candidate.ownerPartyProfileId, candidateEffectiveAt)
                .any { it.id != candidate.id && it.toTargetScope() == candidate.toTargetScope() }
            if (targetAlreadyExists) {
                throw ConflictException(
                    "active limit rule already exists for target scope: ${request.targetScopeHash.value}"
                )
            }
        }

        if (changeRequestRepository.findPendingForTarget(tenantId, request.targetScopeHash, now).isNotEmpty()) {
            throw ConflictException(
                "pending limit change already exists for target scope: ${request.targetScopeHash.value}"
            )
        }
    }

    private fun requireSameIdempotentRequest(
        existingRequest: LimitRuleChangeRequest,
        command: RequestLimitRuleChangeCommand,
    ) {
        val actor = command.toChangeActor()
        val sameRequest =
            existingRequest.changeType.name == command.changeType.name &&
                existingRequest.requestedBy == actor &&
                existingRequest.requestActorContext == command.toRequestActorContext(actor) &&
                existingRequest.requesterPartyProfileId == command.toRequesterPartyProfileId(actor) &&
                existingRequest.authorityDelegationId == command.authorityDelegationId
                    ?.let { PartyProfileDelegationId.of(it) } &&
                existingRequest.targetOwnerPartyProfileId == PartyProfileId.of(command.targetOwnerPartyProfileId) &&
                commandLimitRuleMatches(existingRequest, command) &&
                commandTargetMandateMatches(existingRequest, command) &&
                expectedVersionMatches(existingRequest, command) &&
                requestedDraftMatches(existingRequest, command.requestedRule) &&
                existingRequest.effectiveAt == command.effectiveAt &&
                existingRequest.expiresAt == command.expiresAt &&
                existingRequest.policyContext.value == command.policyContext &&
                existingRequest.metadata.value == command.metadata

        if (!sameRequest) {
            throw IdempotencyConflictException(
                "idempotency key already belongs to a different limit change request"
            )
        }
    }

    private fun commandLimitRuleMatches(
        request: LimitRuleChangeRequest,
        command: RequestLimitRuleChangeCommand,
    ): Boolean =
        command.changeType == LimitRuleChangeType.CREATE ||
            request.limitRuleId?.value == command.limitRuleId

    private fun commandTargetMandateMatches(
        request: LimitRuleChangeRequest,
        command: RequestLimitRuleChangeCommand,
    ): Boolean {
        val commandMandateId = when {
            command.requestedRule != null -> command.toTargetMandateId()?.value
            command.targetMandateId != null -> command.targetMandateId
            else -> return true
        }
        return request.targetMandateId?.value == commandMandateId
    }

    private fun expectedVersionMatches(
        request: LimitRuleChangeRequest,
        command: RequestLimitRuleChangeCommand,
    ): Boolean =
        command.changeType == LimitRuleChangeType.CREATE ||
            request.beforeRule?.value?.get("version") == command.expectedVersion

    private fun requestedDraftMatches(
        request: LimitRuleChangeRequest,
        draft: LimitRuleDraft?,
    ): Boolean {
        if (draft == null) return true
        val snapshot = request.requestedRule.value
        val expectedValues = mutableMapOf<String, Any?>(
            "name" to LimitRuleName.of(draft.name).value,
            "description" to draft.description,
            "limitKind" to draft.limitKind.name,
            "period" to draft.period?.name,
            "amount" to Money.positive(draft.amount, CurrencyCode.of(draft.currency)).amount.toString(),
            "currency" to CurrencyCode.of(draft.currency).value,
            "mandateScope" to draft.mandateScope.name,
            "mandateId" to draft.mandateId?.toString(),
            "channelScope" to draft.channelScope.name,
            "channelId" to draft.channelId?.toString(),
            "channelGroupId" to draft.channelGroupId?.toString(),
            "operationTypeId" to draft.operationTypeId?.toString(),
            "usageConsumptionMode" to draft.usageConsumptionMode.name,
            "priority" to draft.priority,
            "endsAt" to draft.endsAt?.toString(),
            "metadata" to draft.metadata,
        )
        draft.configurationId?.let { expectedValues["configurationId"] = it.toString() }
        draft.startsAt?.let { expectedValues["startsAt"] = it.toString() }

        return expectedValues.all { (key, value) -> snapshot[key] == value }
    }

    private inline fun <T> mapValidation(block: () -> T): T =
        try {
            block()
        } catch (exception: IllegalArgumentException) {
            throw ValidationException(
                message = exception.message ?: "limit change command validation failed",
                cause = exception,
            )
        }

    private companion object {
        val PENDING_STATUSES = setOf(
            LimitRuleChangeStatus.REQUESTED,
            LimitRuleChangeStatus.APPROVED,
            LimitRuleChangeStatus.PENDING_EFFECTIVE_DATE,
        )
    }
}
