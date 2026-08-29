package io.github.sepfins.limitguard.application.change

import io.github.sepfins.limitguard.api.LimitRuleChangeType
import io.github.sepfins.limitguard.api.commands.LimitRuleDraft
import io.github.sepfins.limitguard.api.commands.RequestLimitRuleChangeCommand
import io.github.sepfins.limitguard.api.errors.ConflictException
import io.github.sepfins.limitguard.api.errors.IdempotencyConflictException
import io.github.sepfins.limitguard.api.errors.ValidationException
import io.github.sepfins.limitguard.api.results.LimitChangeRequestResult
import io.github.sepfins.limitguard.domain.change.ChangeActor
import io.github.sepfins.limitguard.domain.change.LimitRuleChangeEventType
import io.github.sepfins.limitguard.domain.change.LimitRuleChangeRequest
import io.github.sepfins.limitguard.domain.change.LimitRuleChangeStatus
import io.github.sepfins.limitguard.domain.common.CurrencyCode
import io.github.sepfins.limitguard.domain.common.IdempotencyKey
import io.github.sepfins.limitguard.domain.common.LimitChangeRequestId
import io.github.sepfins.limitguard.domain.common.LimitRuleId
import io.github.sepfins.limitguard.domain.common.Metadata
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
    private val mandateValidator: LimitRuleMandateValidator,
    private val changeRequestFactory: LimitChangeRequestFactory,
    private val changeEventFactory: LimitChangeEventFactory,
    private val policyRunner: LimitChangePolicyRunner,
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
                val requestedEvent = changeEventRepository
                    .findByChangeRequest(tenantId, existingRequest.id)
                    .firstOrNull { it.eventType == LimitRuleChangeEventType.REQUESTED }
                val originalRequestedRule = requestedEvent?.afterRule ?: existingRequest.requestedRule
                val requestedEventPayload = requestedEvent?.eventPayload?.value
                val originalEffectiveAt = if (requestedEventPayload?.containsKey("effectiveAt") == true) {
                    (requestedEventPayload["effectiveAt"] as? String)?.let(Instant::parse)
                } else {
                    existingRequest.effectiveAt
                }
                requireSameIdempotentRequest(
                    existingRequest = existingRequest,
                    command = command,
                    originalRequestedRule = originalRequestedRule,
                    originalEffectiveAt = originalEffectiveAt,
                )
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
            mandateValidator.validate(candidate, command.effectiveAt ?: now)
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

            val authorization = policyRunner.authorize(requested, now)
            val policyRequest = authorization.request
            var policyCandidate = candidate
            val persistedApproved = when (authorization) {
                is LimitChangeAuthorization.Failed -> {
                    val reason = authorization.evaluation.message
                        ?: "Policy ${authorization.policy.code.value} evaluation failed"
                    val failed = changeRequestRepository.update(
                        tenantId,
                        policyRequest.fail(reason, now),
                    )
                    changeEventRepository.insert(
                        tenantId,
                        changeEventFactory.failed(
                            before = policyRequest,
                            after = failed,
                            now = now,
                            failureReason = reason,
                            policyId = authorization.policy.id,
                            eventPayload = Metadata(
                                mapOf("evaluationId" to authorization.evaluation.id.value.toString())
                            ),
                        ),
                    )
                    return@inTransaction failed.toResult()
                }
                is LimitChangeAuthorization.ManualApprovalRequired -> {
                    candidateAfterPolicyActions(candidate, requested, policyRequest, now)
                    val pending = if (policyRequest == requested) {
                        requested
                    } else {
                        changeRequestRepository.update(tenantId, policyRequest)
                    }
                    return@inTransaction pending.toResult()
                }
                is LimitChangeAuthorization.Denied -> {
                    val reason = authorization.policy.denialMessage
                        ?: authorization.evaluation.message
                        ?: "Denied by policy ${authorization.policy.code.value}"
                    val rejected = changeRequestRepository.update(
                        tenantId,
                        policyRequest.reject(reason, now),
                    )
                    changeEventRepository.insert(
                        tenantId,
                        changeEventFactory.rejected(
                            before = policyRequest,
                            after = rejected,
                            now = now,
                            actor = ChangeActor.System,
                            eventPayload = Metadata(
                                mapOf(
                                    "policyCode" to authorization.policy.code.value,
                                    "denialCode" to authorization.policy.denialCode,
                                    "evaluationId" to authorization.evaluation.id.value.toString(),
                                )
                            ),
                            policyId = authorization.policy.id,
                        ),
                    )
                    return@inTransaction rejected.toResult()
                }
                is LimitChangeAuthorization.Allowed -> {
                    policyCandidate = candidateAfterPolicyActions(candidate, requested, policyRequest, now)
                    val approved = policyRequest.approve(
                        approvedRule = policyRequest.requestedRule,
                        reason = authorization.evaluation.message
                            ?: "Automatically approved by policy ${authorization.policy.code.value}",
                        at = now,
                    )
                    changeRequestRepository.update(tenantId, approved).also { persisted ->
                        changeEventRepository.insert(
                            tenantId,
                            changeEventFactory.approved(
                                before = policyRequest,
                                after = persisted,
                                now = now,
                                eventPayload = Metadata(
                                    mapOf(
                                        "policyCode" to authorization.policy.code.value,
                                        "evaluationId" to authorization.evaluation.id.value.toString(),
                                    )
                                ),
                                policyId = authorization.policy.id,
                            ),
                        )
                    }
                }
            }

            val effectiveAt = persistedApproved.effectiveAt
            if (effectiveAt != null && effectiveAt > now) {
                val scheduled = persistedApproved.schedule(effectiveAt, now)
                val persistedScheduled = changeRequestRepository.update(tenantId, scheduled)
                changeEventRepository.insert(
                    tenantId,
                    changeEventFactory.scheduled(persistedApproved, persistedScheduled, now),
                )
                return@inTransaction persistedScheduled.toResult()
            }

            applicationWorkflow.applyInCurrentTransaction(
                request = persistedApproved,
                candidate = policyCandidate,
                actor = ChangeActor.System,
                applicationTime = now,
            ).toResult()
        }
    }

    private fun candidateAfterPolicyActions(
        originalCandidate: LimitRule,
        originalRequest: LimitRuleChangeRequest,
        policyRequest: LimitRuleChangeRequest,
        now: Instant,
    ): LimitRule {
        if (policyRequest == originalRequest) return originalCandidate

        val candidate = if (policyRequest.requestedRule == originalRequest.requestedRule) {
            originalCandidate
        } else {
            LimitRuleSnapshotter.restoreRule(
                snapshot = policyRequest.requestedRule,
                createdAt = originalCandidate.createdAt,
                updatedAt = now,
            )
        }
        mandateValidator.validate(candidate, policyRequest.effectiveAt ?: now)
        requireTargetAvailable(policyRequest.tenantId, candidate, policyRequest, now)
        return candidate
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
                    it.id != request.id &&
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

        val targetHasAnotherPendingRequest = changeRequestRepository
            .findPendingForTarget(tenantId, request.targetScopeHash, now)
            .any { it.id != request.id }
        if (targetHasAnotherPendingRequest) {
            throw ConflictException(
                "pending limit change already exists for target scope: ${request.targetScopeHash.value}"
            )
        }
    }

    private fun requireSameIdempotentRequest(
        existingRequest: LimitRuleChangeRequest,
        command: RequestLimitRuleChangeCommand,
        originalRequestedRule: Metadata,
        originalEffectiveAt: Instant?,
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
                requestedDraftMatches(originalRequestedRule, command.requestedRule) &&
                originalEffectiveAt == command.effectiveAt &&
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
        requestedRule: Metadata,
        draft: LimitRuleDraft?,
    ): Boolean {
        if (draft == null) return true
        val snapshot = requestedRule.value
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
