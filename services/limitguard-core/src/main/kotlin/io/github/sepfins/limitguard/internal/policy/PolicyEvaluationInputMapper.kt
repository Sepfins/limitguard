package io.github.sepfins.limitguard.internal.policy

import io.github.sepfins.limitguard.domain.change.ChangeActor
import io.github.sepfins.limitguard.domain.change.LimitRuleChangeRequest
import io.github.sepfins.limitguard.domain.change.RequestActorContext
import io.github.sepfins.limitguard.domain.common.Metadata
import io.github.sepfins.limitguard.domain.policy.PolicyEvaluationContext

internal fun PolicyEvaluationContext.toPolicyEvaluationInput(): Metadata =
    Metadata(
        mapOf(
            "phase" to phase.name,
            "evaluatedAt" to evaluatedAt.toString(),
            "changeRequest" to changeRequest.toPolicyInput(),
            "currentRule" to currentRule?.value,
            "facts" to facts.value,
        )
    )

private fun LimitRuleChangeRequest.toPolicyInput(): Map<String, Any?> =
    mapOf(
        "id" to id.value.toString(),
        "tenantId" to tenantId.value.toString(),
        "changeType" to changeType.name,
        "status" to status.name,
        "limitRuleId" to limitRuleId?.value?.toString(),
        "requestedBy" to requestedBy.toPolicyInput(),
        "requestActorContext" to requestActorContext.toPolicyInput(),
        "requesterPartyProfileId" to requesterPartyProfileId?.value?.toString(),
        "authorityDelegationId" to authorityDelegationId?.value?.toString(),
        "targetOwnerPartyProfileId" to targetOwnerPartyProfileId.value.toString(),
        "targetMandateId" to targetMandateId?.value?.toString(),
        "targetScope" to targetScope.value,
        "targetScopeHash" to targetScopeHash.value,
        "beforeRule" to beforeRule?.value,
        "requestedRule" to requestedRule.value,
        "effectiveAt" to effectiveAt?.toString(),
        "expiresAt" to expiresAt?.toString(),
        "idempotencyKey" to idempotencyKey?.value,
        "metadata" to metadata.value,
    )

private fun ChangeActor.toPolicyInput(): Map<String, Any?> =
    when (this) {
        ChangeActor.System -> mapOf("actorType" to "SYSTEM")
        is ChangeActor.PartyProfileActor -> mapOf(
            "actorType" to "PARTY_PROFILE",
            "partyProfileId" to partyProfileId.value.toString(),
        )
        is ChangeActor.BankWorkerActor -> mapOf(
            "actorType" to "BANK_WORKER",
            "bankWorkerId" to bankWorkerId.value.toString(),
        )
    }

private fun RequestActorContext.toPolicyInput(): Map<String, Any?> =
    when (this) {
        RequestActorContext.System -> mapOf("actorType" to "SYSTEM")
        is RequestActorContext.PartyProfile -> mapOf(
            "actorType" to "PARTY_PROFILE",
            "requesterPartyProfileId" to requesterPartyProfileId.value.toString(),
            "requesterChannelId" to requesterChannelId.value.toString(),
        )
        is RequestActorContext.BankWorker -> mapOf(
            "actorType" to "BANK_WORKER",
            "bankWorkerId" to bankWorkerId.value.toString(),
            "requesterChannelId" to requesterChannelId.value.toString(),
        )
        else -> mapOf("actorType" to (this::class.simpleName ?: "UNKNOWN"))
    }
