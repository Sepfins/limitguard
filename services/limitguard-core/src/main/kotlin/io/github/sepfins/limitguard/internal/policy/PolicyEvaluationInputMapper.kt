package io.github.sepfins.limitguard.internal.policy

import io.github.sepfins.limitguard.domain.change.ChangeActor
import io.github.sepfins.limitguard.domain.change.LimitRuleChangeRequest
import io.github.sepfins.limitguard.domain.change.RequestActorContext
import io.github.sepfins.limitguard.domain.common.Metadata
import io.github.sepfins.limitguard.domain.limit.LimitAmount
import io.github.sepfins.limitguard.domain.policy.PolicyEvaluationContext

internal fun PolicyEvaluationContext.toPolicyEvaluationInput(): Metadata =
    Metadata(
        mapOf(
            "phase" to phase.name,
            "evaluatedAt" to evaluatedAt.toString(),
            "changeRequest" to changeRequest.toPolicyInput(),
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
        "configurationId" to configurationId.value.toString(),
        "currency" to requestedAmount.currency.value,
        "beforeAmount" to (beforeAmount as? LimitAmount.Bounded)?.money?.amount?.toPlainString(),
        "beforeUnlimited" to beforeAmount?.let { it is LimitAmount.Unlimited },
        "requestedAmount" to (requestedAmount as? LimitAmount.Bounded)?.money?.amount?.toPlainString(),
        "requestedUnlimited" to (requestedAmount is LimitAmount.Unlimited),
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
    }

private fun RequestActorContext.toPolicyInput(): Map<String, Any?> =
    when (this) {
        is RequestActorContext.System -> mapOf(
            "shapeVersion" to 2,
            "actorType" to "SYSTEM",
            "requestChannelId" to requestChannelId?.value?.toString(),
        )
        is RequestActorContext.PartyProfile -> mapOf(
            "shapeVersion" to 2,
            "actorType" to "PARTY_PROFILE",
            "actorPartyProfileId" to actorPartyProfileId.value.toString(),
            "requestChannelId" to requestChannelId.value.toString(),
        )
    }
