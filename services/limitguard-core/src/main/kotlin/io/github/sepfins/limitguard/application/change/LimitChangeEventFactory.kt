package io.github.sepfins.limitguard.application.change

import io.github.sepfins.limitguard.domain.change.LimitRuleChangeEvent
import io.github.sepfins.limitguard.domain.change.LimitRuleChangeEventType
import io.github.sepfins.limitguard.domain.change.LimitRuleChangeRequest
import io.github.sepfins.limitguard.domain.common.LimitRuleChangeEventId
import io.github.sepfins.limitguard.domain.common.Metadata
import io.github.sepfins.limitguard.port.ids.IdGenerator
import java.time.Instant

internal class LimitChangeEventFactory(
    private val idGenerator: IdGenerator
) {
    fun requested(
        changeRequest: LimitRuleChangeRequest,
        now: Instant
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
                )
            ),
            message = "Limit rule change requested",
            createdAt = now,
        )
    }

    fun approved(
        before: LimitRuleChangeRequest,
        after: LimitRuleChangeRequest,
        now: Instant,
    ): LimitRuleChangeEvent =
        LimitRuleChangeEvent(
            id = LimitRuleChangeEventId.of(idGenerator.newId()),
            tenantId = after.tenantId,
            changeRequestId = after.id,
            eventType = LimitRuleChangeEventType.APPROVED,
            beforeStatus = before.status,
            afterStatus = after.status,
            beforeRule = before.requestedRule,
            afterRule = after.approvedRule,
            message = "Limit rule change automatically approved",
            createdAt = now,
        )

    fun applied(
        before: LimitRuleChangeRequest,
        after: LimitRuleChangeRequest,
        now: Instant,
    ): LimitRuleChangeEvent =
        LimitRuleChangeEvent(
            id = LimitRuleChangeEventId.of(idGenerator.newId()),
            tenantId = after.tenantId,
            changeRequestId = after.id,
            eventType = LimitRuleChangeEventType.APPLIED,
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
    ): LimitRuleChangeEvent =
        LimitRuleChangeEvent(
            id = LimitRuleChangeEventId.of(idGenerator.newId()),
            tenantId = after.tenantId,
            changeRequestId = after.id,
            eventType = LimitRuleChangeEventType.SCHEDULED,
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
