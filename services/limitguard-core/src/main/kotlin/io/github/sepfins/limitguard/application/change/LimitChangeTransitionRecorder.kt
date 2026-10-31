package io.github.sepfins.limitguard.application.change

import io.github.sepfins.limitguard.domain.change.ChangeActor
import io.github.sepfins.limitguard.domain.change.LimitChangePolicyEvaluation
import io.github.sepfins.limitguard.domain.change.LimitRuleChangeEvent
import io.github.sepfins.limitguard.domain.change.LimitRuleChangeEventType
import io.github.sepfins.limitguard.domain.change.LimitRuleChangeRequest
import io.github.sepfins.limitguard.domain.common.LimitChangePolicyId
import io.github.sepfins.limitguard.domain.common.LimitRuleChangeEventId
import io.github.sepfins.limitguard.domain.common.LimitRuleId
import io.github.sepfins.limitguard.domain.common.Metadata
import io.github.sepfins.limitguard.port.ids.IdGenerator
import io.github.sepfins.limitguard.port.persistence.LimitChangeRequestRepository
import io.github.sepfins.limitguard.port.persistence.LimitRuleChangeEventRepository
import java.time.Instant

/**
 * The only writer of limit change requests and their events: each status change drives the request's
 * state machine, saves the row and records exactly one event in the caller's transaction.
 */
internal class LimitChangeTransitionRecorder(
    private val requests: LimitChangeRequestRepository,
    private val events: LimitRuleChangeEventRepository,
    private val idGenerator: IdGenerator,
) {
    fun request(request: LimitRuleChangeRequest, originalIntent: Metadata, at: Instant): LimitRuleChangeRequest {
        val requested = requests.insert(request.tenantId, request)
        record(
            before = null,
            after = requested,
            type = LimitRuleChangeEventType.REQUESTED,
            actor = requested.requestedBy,
            at = at,
            payload = Metadata(
                mapOf(
                    "changeType" to requested.changeType.name,
                    "effectiveAt" to requested.effectiveAt?.toString(),
                    "originalIntent" to originalIntent.value,
                )
            ),
        )
        return requested
    }

    fun approve(
        request: LimitRuleChangeRequest,
        reason: String?,
        actor: ChangeActor,
        at: Instant,
        payload: Metadata = Metadata.EMPTY,
        policyId: LimitChangePolicyId? = null,
    ): LimitRuleChangeRequest =
        transition(request, request.approve(reason, at), LimitRuleChangeEventType.APPROVED, actor, at, payload, policyId)

    fun schedule(request: LimitRuleChangeRequest, effectiveAt: Instant, actor: ChangeActor, at: Instant): LimitRuleChangeRequest =
        transition(
            request, request.schedule(effectiveAt, at), LimitRuleChangeEventType.SCHEDULED, actor, at,
            payload = Metadata(mapOf("effectiveAt" to effectiveAt.toString())),
        )

    fun apply(request: LimitRuleChangeRequest, limitRuleId: LimitRuleId, actor: ChangeActor, at: Instant): LimitRuleChangeRequest =
        transition(
            request, request.markApplied(limitRuleId, at), LimitRuleChangeEventType.APPLIED, actor, at,
            payload = Metadata(mapOf("limitRuleId" to limitRuleId.value.toString())),
        )

    fun reject(
        request: LimitRuleChangeRequest,
        reason: String,
        actor: ChangeActor,
        at: Instant,
        payload: Metadata = Metadata.EMPTY,
        policyId: LimitChangePolicyId? = null,
    ): LimitRuleChangeRequest =
        transition(request, request.reject(reason, at), LimitRuleChangeEventType.REJECTED, actor, at, payload, policyId)

    fun cancel(
        request: LimitRuleChangeRequest,
        reason: String?,
        actor: ChangeActor,
        at: Instant,
        payload: Metadata = Metadata.EMPTY,
    ): LimitRuleChangeRequest =
        transition(request, request.cancel(reason, at), LimitRuleChangeEventType.CANCELLED, actor, at, payload)

    fun fail(
        request: LimitRuleChangeRequest,
        reason: String,
        at: Instant,
        payload: Metadata = Metadata.EMPTY,
        policyId: LimitChangePolicyId? = null,
    ): LimitRuleChangeRequest =
        transition(
            request, request.fail(reason, at), LimitRuleChangeEventType.FAILED, ChangeActor.System, at,
            payload = Metadata(mapOf("reason" to reason) + payload.value),
            policyId = policyId,
        )

    /** Records a policy run; its actions may have amended [after], which a later transition saves. */
    fun recordPolicyEvaluation(
        before: LimitRuleChangeRequest,
        after: LimitRuleChangeRequest,
        evaluation: LimitChangePolicyEvaluation,
        at: Instant,
    ) {
        record(
            before = before,
            after = after,
            type = LimitRuleChangeEventType.POLICY_EVALUATED,
            actor = ChangeActor.System,
            at = at,
            payload = Metadata(
                mapOf(
                    "evaluationId" to evaluation.id.value.toString(),
                    "phase" to evaluation.phase.name,
                    "outcome" to evaluation.outcome.name,
                    "decision" to evaluation.decision.name,
                    "matched" to evaluation.matched,
                    "actionResult" to evaluation.actionResult.value,
                )
            ),
            policyId = evaluation.policyId,
            message = evaluation.message,
        )
    }

    private fun transition(
        before: LimitRuleChangeRequest,
        next: LimitRuleChangeRequest,
        type: LimitRuleChangeEventType,
        actor: ChangeActor,
        at: Instant,
        payload: Metadata = Metadata.EMPTY,
        policyId: LimitChangePolicyId? = null,
    ): LimitRuleChangeRequest {
        val after = requests.update(before.tenantId, next)
        record(before, after, type, actor, at, payload, policyId)
        return after
    }

    private fun record(
        before: LimitRuleChangeRequest?,
        after: LimitRuleChangeRequest,
        type: LimitRuleChangeEventType,
        actor: ChangeActor,
        at: Instant,
        payload: Metadata,
        policyId: LimitChangePolicyId? = null,
        message: String? = null,
    ) {
        events.insert(
            after.tenantId,
            LimitRuleChangeEvent(
                id = LimitRuleChangeEventId.of(idGenerator.newId()),
                tenantId = after.tenantId,
                changeRequestId = after.id,
                eventType = type,
                actor = actor,
                policyId = policyId,
                beforeStatus = before?.status,
                afterStatus = after.status,
                eventPayload = payload,
                message = message ?: MESSAGES.getValue(type),
                createdAt = at,
            ),
        )
    }

    private companion object {
        val MESSAGES = mapOf(
            LimitRuleChangeEventType.REQUESTED to "Limit rule change requested",
            LimitRuleChangeEventType.APPROVED to "Limit rule change approved",
            LimitRuleChangeEventType.SCHEDULED to "Limit rule change scheduled",
            LimitRuleChangeEventType.APPLIED to "Limit rule change applied",
            LimitRuleChangeEventType.REJECTED to "Limit rule change rejected",
            LimitRuleChangeEventType.CANCELLED to "Limit rule change cancelled",
            LimitRuleChangeEventType.FAILED to "Limit rule change application failed",
            LimitRuleChangeEventType.POLICY_EVALUATED to "Limit change policy evaluated",
        )
    }
}
