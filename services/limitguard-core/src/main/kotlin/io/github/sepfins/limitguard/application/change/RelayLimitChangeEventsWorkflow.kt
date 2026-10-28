package io.github.sepfins.limitguard.application.change

import io.github.sepfins.limitguard.api.LimitChangePolicyEvaluationOutcome
import io.github.sepfins.limitguard.api.LimitRuleChangeEventType as ApiLimitRuleChangeEventType
import io.github.sepfins.limitguard.api.LimitRuleChangeStatus as ApiLimitRuleChangeStatus
import io.github.sepfins.limitguard.api.LimitRuleChangeType as ApiLimitRuleChangeType
import io.github.sepfins.limitguard.api.commands.RelayLimitChangeEventsCommand
import io.github.sepfins.limitguard.api.errors.ValidationException
import io.github.sepfins.limitguard.api.results.LimitChangeEventMessage
import io.github.sepfins.limitguard.application.toApi
import io.github.sepfins.limitguard.domain.change.LimitRuleChangeEventType
import io.github.sepfins.limitguard.port.persistence.LimitChangeEventOutbox
import io.github.sepfins.limitguard.port.persistence.LimitChangeRequestRepository
import io.github.sepfins.limitguard.port.persistence.LimitRuleChangeEventRepository
import io.github.sepfins.limitguard.port.persistence.LimitRuleRevisionRepository
import io.github.sepfins.limitguard.port.persistence.OutboxedLimitChangeEvent
import io.github.sepfins.limitguard.port.transactions.TransactionRunner

internal class RelayLimitChangeEventsWorkflow(
    private val outbox: LimitChangeEventOutbox,
    private val changeEventRepository: LimitRuleChangeEventRepository,
    private val changeRequestRepository: LimitChangeRequestRepository,
    private val limitRuleRevisionRepository: LimitRuleRevisionRepository,
    private val transactionRunner: TransactionRunner,
) {
    fun execute(command: RelayLimitChangeEventsCommand): Int {
        if (command.limit <= 0) throw ValidationException("limit must be greater than zero")

        // A throwing sink rolls back the dequeue, so the next call sends the same batch.
        return transactionRunner.inTransaction {
            val queued = outbox.lockOldest(command.limit)
            if (queued.isNotEmpty()) {
                command.sink.relay(queued.map(::message))
                outbox.delete(queued)
            }
            queued.size
        }
    }

    private fun message(queued: OutboxedLimitChangeEvent): LimitChangeEventMessage {
        val event = requireNotNull(changeEventRepository.findById(queued.tenantId, queued.eventId))
        val request = requireNotNull(changeRequestRepository.findById(queued.tenantId, event.changeRequestId))
        val revision = if (event.eventType == LimitRuleChangeEventType.APPLIED) {
            limitRuleRevisionRepository.findByChangeRequest(queued.tenantId, request.id).single()
        } else {
            null
        }
        val policyEvaluated = event.eventType == LimitRuleChangeEventType.POLICY_EVALUATED

        return LimitChangeEventMessage(
            eventId = event.id.value,
            tenantId = event.tenantId.value,
            changeRequestId = request.id.value,
            eventType = ApiLimitRuleChangeEventType.valueOf(event.eventType.name),
            actor = event.actor.toResult(),
            beforeStatus = event.beforeStatus?.let { ApiLimitRuleChangeStatus.valueOf(it.name) },
            afterStatus = event.afterStatus?.let { ApiLimitRuleChangeStatus.valueOf(it.name) },
            occurredAt = requireNotNull(event.createdAt),
            targetOwnerPartyProfileId = request.targetOwnerPartyProfileId.value,
            configurationId = request.configurationId.value,
            targetMandateId = request.targetMandateId?.value,
            limitRuleId = request.limitRuleId?.value,
            requesterPartyProfileId = request.requesterPartyProfileId?.value,
            requestChannelId = request.requestActorContext.requestChannelId?.value,
            changeType = ApiLimitRuleChangeType.valueOf(request.changeType.name),
            requestedAmount = request.requestedAmount.toApi(),
            currency = request.requestedAmount.currency.value,
            effectiveAt = request.effectiveAt,
            beforeAmount = revision?.beforeAmount?.toApi(),
            afterAmount = revision?.afterAmount?.toApi(),
            policyId = event.policyId?.value?.takeIf { policyEvaluated },
            policyOutcome = if (policyEvaluated) {
                LimitChangePolicyEvaluationOutcome.valueOf(event.eventPayload.value["outcome"] as String)
            } else {
                null
            },
        )
    }
}
