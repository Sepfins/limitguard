package io.github.sepfins.limitguard.application.change

import io.github.sepfins.limitguard.api.TrustedChangeActorContext
import io.github.sepfins.limitguard.api.commands.RequestLimitRuleChangeCommand
import io.github.sepfins.limitguard.api.errors.ConflictException
import io.github.sepfins.limitguard.api.errors.IdempotencyConflictException
import io.github.sepfins.limitguard.api.errors.LimitguardException
import io.github.sepfins.limitguard.api.errors.ValidationException
import io.github.sepfins.limitguard.api.results.LimitChangeRequestResult
import io.github.sepfins.limitguard.domain.change.ChangeActor
import io.github.sepfins.limitguard.domain.change.LimitRuleChangeEventType
import io.github.sepfins.limitguard.domain.common.IdempotencyKey
import io.github.sepfins.limitguard.domain.common.LimitChangeRequestId
import io.github.sepfins.limitguard.domain.common.LimitRuleId
import io.github.sepfins.limitguard.domain.common.Metadata
import io.github.sepfins.limitguard.domain.common.TenantId
import io.github.sepfins.limitguard.port.ids.IdGenerator
import io.github.sepfins.limitguard.port.persistence.LimitChangeRequestRepository
import io.github.sepfins.limitguard.port.persistence.LimitRuleChangeEventRepository
import io.github.sepfins.limitguard.port.time.ClockProvider
import io.github.sepfins.limitguard.port.transactions.TransactionRunner

internal class RequestLimitRuleChangeWorkflow(
    private val commandValidator: ChangeCommandValidator,
    private val referenceResolver: ChangeReferenceResolver,
    private val candidateFactory: LimitRuleCandidateFactory,
    private val mandateValidator: LimitRuleMandateValidator,
    private val eligibility: LimitChangeEligibilityValidator,
    private val changeRequestFactory: LimitChangeRequestFactory,
    private val transitions: LimitChangeTransitionRecorder,
    private val policyRunner: LimitChangePolicyRunner,
    private val applicationWorkflow: LimitChangeApplicationWorkflow,
    private val changeRequestRepository: LimitChangeRequestRepository,
    private val changeEventRepository: LimitRuleChangeEventRepository,
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

            val target = command.toTarget()
            val locked = locks.acquireTarget(tenantId, target)
            val facts = referenceResolver.resolve(command, locked)
            val changeType = candidateFactory.changeType(command.operation, facts)
            val request = changeRequestFactory.create(
                id = LimitChangeRequestId.of(idGenerator.newId()),
                command = command,
                changeType = changeType,
                requestedAmount = candidateFactory.requestedAmount(command.operation, facts),
                facts = facts,
                now = now,
            )
            val applicationTime = command.effectiveAt?.takeIf { it > now } ?: now
            val candidate = candidateFactory.candidate(request, locked, { LimitRuleId.of(idGenerator.newId()) }, applicationTime)
            eligibility.validateSubmission(command, candidate, now)
            mandateValidator.validate(candidate, applicationTime)

            // The pending-target index makes a concurrent request for the same target wait, then conflict.
            val requested = try {
                transactionRunner.inSavepoint {
                    transitions.request(request, LimitChangeCreationIntent.capture(command), now)
                }
            } catch (exception: LimitguardException) {
                if (exception !is ConflictException && exception !is IdempotencyConflictException) throw exception
                replay(command, tenantId)?.let { return@inTransaction it }
                throw ConflictException("pending limit change already exists for the target", exception)
            }
            // A concurrent request for an empty target may have created its rule before this insert could proceed.
            if (locked.rule == null && locks.currentRule(tenantId, target) != null) {
                throw ConflictException("limit rule for the target was created concurrently; retry")
            }

            val authorization = policyRunner.authorize(
                request = requested,
                at = now,
            )
            val policyRequest = authorization.request
            when (authorization) {
                is LimitChangeAuthorization.Failed -> {
                    val reason = authorization.evaluation.message
                        ?: "Policy ${authorization.policy.code.value} evaluation failed"
                    return@inTransaction transitions.fail(
                        request = policyRequest,
                        reason = reason,
                        at = now,
                        payload = Metadata(mapOf("evaluationId" to authorization.evaluation.id.value.toString())),
                        policyId = authorization.policy.id,
                    ).toResult()
                }
                is LimitChangeAuthorization.ManualApprovalRequired -> {
                    if (policyRequest != requested) applicationWorkflow.validate(policyRequest, locked, now)
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
                    return@inTransaction transitions.reject(
                        request = policyRequest,
                        reason = reason,
                        actor = ChangeActor.System,
                        at = now,
                        payload = Metadata(
                            mapOf(
                                "policyCode" to authorization.policy.code.value,
                                "denialCode" to authorization.policy.denialCode,
                                "evaluationId" to authorization.evaluation.id.value.toString(),
                            )
                        ),
                        policyId = authorization.policy.id,
                    ).toResult()
                }
                is LimitChangeAuthorization.Allowed -> {
                    applicationWorkflow.approveAndApply(
                        request = policyRequest,
                        target = locked,
                        actor = ChangeActor.System,
                        reason = authorization.evaluation.message
                            ?: "Automatically approved by policy ${authorization.policy.code.value}",
                        at = now,
                        eventPayload = Metadata(mapOf(
                            "policyCode" to authorization.policy.code.value,
                            "evaluationId" to authorization.evaluation.id.value.toString(),
                        )),
                        policyId = authorization.policy.id,
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
