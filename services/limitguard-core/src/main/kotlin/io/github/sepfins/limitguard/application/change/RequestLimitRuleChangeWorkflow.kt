package io.github.sepfins.limitguard.application.change

import io.github.sepfins.limitguard.api.TrustedChangeActorContext
import io.github.sepfins.limitguard.api.commands.RequestLimitRuleChangeCommand
import io.github.sepfins.limitguard.api.errors.IdempotencyConflictException
import io.github.sepfins.limitguard.api.errors.ValidationException
import io.github.sepfins.limitguard.api.results.LimitChangeRequestResult
import io.github.sepfins.limitguard.domain.change.ChangeActor
import io.github.sepfins.limitguard.domain.change.LimitRuleChangeEventType
import io.github.sepfins.limitguard.domain.change.LimitRuleChangeRequest
import io.github.sepfins.limitguard.domain.common.IdempotencyKey
import io.github.sepfins.limitguard.domain.common.LimitChangeRequestId
import io.github.sepfins.limitguard.domain.common.LimitRuleId
import io.github.sepfins.limitguard.domain.common.Metadata
import io.github.sepfins.limitguard.domain.common.TenantId
import io.github.sepfins.limitguard.domain.limit.LimitRule
import io.github.sepfins.limitguard.internal.json.JsonValueEquality
import io.github.sepfins.limitguard.port.ids.IdGenerator
import io.github.sepfins.limitguard.port.persistence.LimitChangeRequestRepository
import io.github.sepfins.limitguard.port.persistence.LimitRuleChangeEventRepository
import io.github.sepfins.limitguard.port.time.ClockProvider
import io.github.sepfins.limitguard.port.transactions.TransactionRunner
import java.time.Instant

internal class RequestLimitRuleChangeWorkflow(
    private val commandValidator: ChangeCommandValidator,
    private val referenceResolver: ChangeReferenceResolver,
    private val candidateFactory: LimitRuleCandidateFactory,
    private val mandateValidator: LimitRuleMandateValidator,
    private val eligibility: LimitChangeEligibilityValidator,
    private val changeRequestFactory: LimitChangeRequestFactory,
    private val changeEventFactory: LimitChangeEventFactory,
    private val policyRunner: LimitChangePolicyRunner,
    private val applicationWorkflow: LimitChangeApplicationWorkflow,
    private val changeRequestRepository: LimitChangeRequestRepository,
    private val changeEventRepository: LimitRuleChangeEventRepository,
    private val targets: LimitChangeTargets,
    private val locks: LimitChangeLocks,
    private val idGenerator: IdGenerator,
    private val clockProvider: ClockProvider,
    private val transactionRunner: TransactionRunner,
) {
    fun execute(command: RequestLimitRuleChangeCommand, trustedActorContext: TrustedChangeActorContext?): LimitChangeRequestResult = mapValidation {
        command.requestedBy.requireTrustedAuthority(trustedActorContext)
        val now = clockProvider.now()
        commandValidator.validateStructure(command)
        val tenantId = TenantId.of(command.tenantId)

        transactionRunner.inTransaction {
            replay(command, tenantId)?.let { return@inTransaction it }

            eligibility.validateTenant(tenantId)
            commandValidator.validateEligibility(command, now)

            val lockedTargets = locks.acquireTargets(tenantId, listOf(targets.lockPlan(command)))
            replay(command, tenantId)?.let { return@inTransaction it }
            val facts = referenceResolver.resolve(command)
            val changeType = candidateFactory.changeType(command.operation, facts.currentRule)
            val candidate = candidateFactory.createCandidate(command, facts, { LimitRuleId.of(idGenerator.newId()) }, now)
            eligibility.validateSubmission(command, candidate, now)
            mandateValidator.validate(candidate, command.effectiveAt ?: now)
            val requestId = LimitChangeRequestId.of(idGenerator.newId())
            val target = targets.requireAvailable(candidate, requestId, now, lockedTargets)
            val request = changeRequestFactory.create(
                id = requestId,
                command = command,
                changeType = changeType,
                requestedRule = candidate,
                currentRule = facts.currentRule,
                targetScope = target,
                now = now,
            )

            val requested = changeRequestRepository.insert(tenantId, request)
            changeEventRepository.insert(tenantId, changeEventFactory.requested(
                requested,
                now,
                originalIntent = LimitChangeCreationIntent.capture(command),
            ))

            val authorization = policyRunner.authorize(
                request = requested,
                at = now,
            )
            val policyRequest = authorization.request
            when (authorization) {
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
                    candidateAfterPolicyActions(candidate, requested, policyRequest, now, lockedTargets)
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
                    val policyCandidate = candidateAfterPolicyActions(candidate, requested, policyRequest, now, lockedTargets)
                    applicationWorkflow.approveAndApply(
                        request = policyRequest,
                        candidate = policyCandidate,
                        actor = ChangeActor.System,
                        reason = authorization.evaluation.message
                            ?: "Automatically approved by policy ${authorization.policy.code.value}",
                        at = now,
                        eventPayload = Metadata(mapOf(
                            "policyCode" to authorization.policy.code.value,
                            "evaluationId" to authorization.evaluation.id.value.toString(),
                        )),
                        policyId = authorization.policy.id,
                        lockedTargets = lockedTargets,
                    ).toResult()
                }
            }
        }
    }

    private fun replay(command: RequestLimitRuleChangeCommand, tenantId: TenantId): LimitChangeRequestResult? {
        val existingRequest = command.idempotencyKey
            ?.let { IdempotencyKey.of(it) }
            ?.let { changeRequestRepository.findByIdempotencyKey(tenantId, it) } ?: return null
        val originalIntent = changeEventRepository.findByChangeRequest(tenantId, existingRequest.id)
            .firstOrNull { it.eventType == LimitRuleChangeEventType.REQUESTED }
            ?.eventPayload?.value?.get("originalIntent") as? Map<*, *>
            ?: throw IdempotencyConflictException("original creation evidence is unavailable for this request")
        if (!LimitChangeCreationIntent.matches(originalIntent, command)) {
            throw IdempotencyConflictException("idempotency key already belongs to a different limit change request")
        }
        return existingRequest.toResult()
    }

    private fun candidateAfterPolicyActions(
        originalCandidate: LimitRule,
        originalRequest: LimitRuleChangeRequest,
        policyRequest: LimitRuleChangeRequest,
        now: Instant,
        lockedTargets: LockedLimitChangeTargets,
    ): LimitRule {
        if (policyRequest == originalRequest) return originalCandidate

        val candidate = if (
            JsonValueEquality.objectsEqual(
                policyRequest.requestedRule.value,
                originalRequest.requestedRule.value,
            )
        ) {
            originalCandidate
        } else {
            LimitRuleSnapshotter.restoreRule(
                snapshot = policyRequest.requestedRule,
                createdAt = originalCandidate.createdAt,
                updatedAt = now,
            )
        }
        mandateValidator.validate(candidate, policyRequest.effectiveAt ?: now)
        eligibility.validateApprovedIntent(policyRequest, candidate, now)
        targets.requireAvailable(candidate, policyRequest.id, now, lockedTargets)
        return candidate
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

}
