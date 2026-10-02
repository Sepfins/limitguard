package io.github.sepfins.limitguard.application.change

import io.github.sepfins.limitguard.domain.change.ChangeActor
import io.github.sepfins.limitguard.domain.change.LimitChangePolicyEvaluation
import io.github.sepfins.limitguard.domain.change.LimitRuleChangeEvent
import io.github.sepfins.limitguard.domain.change.LimitRuleChangeEventType
import io.github.sepfins.limitguard.domain.change.LimitRuleChangeRequest
import io.github.sepfins.limitguard.domain.common.LimitChangePolicyId
import io.github.sepfins.limitguard.domain.common.LimitRuleChangeEventId
import io.github.sepfins.limitguard.domain.common.Metadata
import io.github.sepfins.limitguard.port.ids.IdGenerator
import java.time.Instant

internal class LimitChangeEventFactory(
    private val idGenerator: IdGenerator
) {
    fun requested(
        changeRequest: LimitRuleChangeRequest,
        now: Instant,
        originalIntent: Metadata? = null,
    ): LimitRuleChangeEvent {
        return LimitRuleChangeEvent(
            id = LimitRuleChangeEventId.of(idGenerator.newId()),
            tenantId = changeRequest.tenantId,
            changeRequestId = changeRequest.id,
            eventType = LimitRuleChangeEventType.REQUESTED,
            actor = changeRequest.requestedBy,
            beforeStatus = null,
            afterStatus = changeRequest.status,
            beforeRule = changeRequest.beforeRule,
            afterRule = changeRequest.requestedRule,
            eventPayload = Metadata(
                mapOf(
                    "changeType" to changeRequest.changeType.name,
                    "targetScopeHash" to changeRequest.targetScopeHash.value,
                    "effectiveAt" to changeRequest.effectiveAt?.toString(),
                ) + (originalIntent?.let { mapOf("originalIntent" to it.value) } ?: emptyMap())
            ),
            message = "Limit rule change requested",
            createdAt = now,
        )
    }

    fun approved(
        before: LimitRuleChangeRequest,
        after: LimitRuleChangeRequest,
        now: Instant,
        actor: ChangeActor = ChangeActor.System,
        eventPayload: Metadata = Metadata.EMPTY,
        policyId: LimitChangePolicyId? = null,
    ): LimitRuleChangeEvent =
        LimitRuleChangeEvent(
            id = LimitRuleChangeEventId.of(idGenerator.newId()),
            tenantId = after.tenantId,
            changeRequestId = after.id,
            eventType = LimitRuleChangeEventType.APPROVED,
            actor = actor,
            policyId = policyId,
            beforeStatus = before.status,
            afterStatus = after.status,
            beforeRule = before.requestedRule,
            afterRule = after.approvedRule,
            eventPayload = eventPayload,
            message = "Limit rule change approved",
            createdAt = now,
        )

    fun rejected(
        before: LimitRuleChangeRequest,
        after: LimitRuleChangeRequest,
        now: Instant,
        actor: ChangeActor,
        eventPayload: Metadata = Metadata.EMPTY,
        policyId: LimitChangePolicyId? = null,
    ): LimitRuleChangeEvent =
        LimitRuleChangeEvent(
            id = LimitRuleChangeEventId.of(idGenerator.newId()),
            tenantId = after.tenantId,
            changeRequestId = after.id,
            eventType = LimitRuleChangeEventType.REJECTED,
            actor = actor,
            policyId = policyId,
            beforeStatus = before.status,
            afterStatus = after.status,
            beforeRule = before.requestedRule,
            afterRule = after.requestedRule,
            eventPayload = eventPayload,
            message = "Limit rule change rejected",
            createdAt = now,
        )

    fun policyEvaluated(
        before: LimitRuleChangeRequest,
        after: LimitRuleChangeRequest,
        evaluation: LimitChangePolicyEvaluation,
        now: Instant,
    ): LimitRuleChangeEvent =
        LimitRuleChangeEvent(
            id = LimitRuleChangeEventId.of(idGenerator.newId()),
            tenantId = after.tenantId,
            changeRequestId = after.id,
            eventType = LimitRuleChangeEventType.POLICY_EVALUATED,
            actor = ChangeActor.System,
            policyId = evaluation.policyId,
            beforeStatus = before.status,
            afterStatus = after.status,
            beforeRule = before.requestedRule,
            afterRule = after.requestedRule,
            eventPayload = Metadata(
                mapOf(
                    "evaluationId" to evaluation.id.value.toString(),
                    "phase" to evaluation.phase.name,
                    "outcome" to evaluation.outcome.name,
                    "decision" to evaluation.decision.name,
                    "matched" to evaluation.matched,
                    "actionResult" to evaluation.actionResult.value,
                )
            ),
            message = evaluation.message ?: "Limit change policy evaluated",
            createdAt = now,
        )

    fun cancelled(
        before: LimitRuleChangeRequest,
        after: LimitRuleChangeRequest,
        now: Instant,
        actor: ChangeActor,
        eventPayload: Metadata = Metadata.EMPTY,
    ): LimitRuleChangeEvent =
        LimitRuleChangeEvent(
            id = LimitRuleChangeEventId.of(idGenerator.newId()),
            tenantId = after.tenantId,
            changeRequestId = after.id,
            eventType = LimitRuleChangeEventType.CANCELLED,
            actor = actor,
            beforeStatus = before.status,
            afterStatus = after.status,
            beforeRule = before.approvedRule ?: before.requestedRule,
            afterRule = after.approvedRule ?: after.requestedRule,
            eventPayload = eventPayload,
            message = "Limit rule change cancelled",
            createdAt = now,
        )

    fun failed(
        before: LimitRuleChangeRequest,
        after: LimitRuleChangeRequest,
        now: Instant,
        failureReason: String,
        policyId: LimitChangePolicyId? = null,
        eventPayload: Metadata = Metadata.EMPTY,
    ): LimitRuleChangeEvent =
        LimitRuleChangeEvent(
            id = LimitRuleChangeEventId.of(idGenerator.newId()),
            tenantId = after.tenantId,
            changeRequestId = after.id,
            eventType = LimitRuleChangeEventType.FAILED,
            actor = ChangeActor.System,
            policyId = policyId,
            beforeStatus = before.status,
            afterStatus = after.status,
            beforeRule = before.approvedRule ?: before.requestedRule,
            afterRule = after.approvedRule ?: after.requestedRule,
            eventPayload = Metadata(mapOf("reason" to failureReason) + eventPayload.value),
            message = "Limit rule change application failed",
            createdAt = now,
        )

    fun applied(
        before: LimitRuleChangeRequest,
        after: LimitRuleChangeRequest,
        now: Instant,
        actor: ChangeActor = ChangeActor.System,
    ): LimitRuleChangeEvent =
        LimitRuleChangeEvent(
            id = LimitRuleChangeEventId.of(idGenerator.newId()),
            tenantId = after.tenantId,
            changeRequestId = after.id,
            eventType = LimitRuleChangeEventType.APPLIED,
            actor = actor,
            beforeStatus = before.status,
            afterStatus = after.status,
            beforeRule = before.beforeRule,
            afterRule = after.approvedRule,
            eventPayload = Metadata(
                mapOf("limitRuleId" to requireNotNull(after.limitRuleId).value.toString())
            ),
            message = "Limit rule change applied",
            createdAt = now,
        )

    fun scheduled(
        before: LimitRuleChangeRequest,
        after: LimitRuleChangeRequest,
        now: Instant,
        actor: ChangeActor = ChangeActor.System,
    ): LimitRuleChangeEvent =
        LimitRuleChangeEvent(
            id = LimitRuleChangeEventId.of(idGenerator.newId()),
            tenantId = after.tenantId,
            changeRequestId = after.id,
            eventType = LimitRuleChangeEventType.SCHEDULED,
            actor = actor,
            beforeStatus = before.status,
            afterStatus = after.status,
            beforeRule = before.approvedRule,
            afterRule = after.approvedRule,
            eventPayload = Metadata(
                mapOf("effectiveAt" to requireNotNull(after.effectiveAt).toString())
            ),
            message = "Limit rule change scheduled",
            createdAt = now,
        )
}
