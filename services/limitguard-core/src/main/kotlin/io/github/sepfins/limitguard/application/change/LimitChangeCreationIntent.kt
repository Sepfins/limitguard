package io.github.sepfins.limitguard.application.change

import io.github.sepfins.limitguard.api.commands.RequestLimitRuleChangeCommand
import io.github.sepfins.limitguard.api.errors.InfrastructureException
import io.github.sepfins.limitguard.domain.common.Metadata
import io.github.sepfins.limitguard.domain.limit.LimitRule
import io.github.sepfins.limitguard.domain.limit.LimitRuleTargetScope
import io.github.sepfins.limitguard.internal.hashing.TargetScopeCanonicalizer
import io.github.sepfins.limitguard.internal.json.JsonValueEquality

internal object LimitChangeCreationIntent {
    fun capture(
        command: RequestLimitRuleChangeCommand,
        candidate: LimitRule,
        target: LimitRuleTargetScope,
    ): Metadata {
        val resolvedRule = LimitRuleSnapshotter.ruleSnapshot(candidate)
        return Metadata(mapOf(
            "submission" to normalize(command, resolvedRule.value),
            "resolvedRule" to resolvedRule.value,
            "targetScope" to TargetScopeCanonicalizer.snapshot(target).value,
        ))
    }

    fun matches(intent: Map<*, *>, command: RequestLimitRuleChangeCommand): Boolean {
        val resolvedRule = intent["resolvedRule"] as? Map<*, *>
            ?: throw InfrastructureException("original creation intent is missing its resolved rule")
        val submission = intent["submission"] as? Map<*, *>
            ?: throw InfrastructureException("original creation intent is missing its submission")
        return JsonValueEquality.valuesEqual(submission, normalize(command, resolvedRule))
    }

    private fun normalize(command: RequestLimitRuleChangeCommand, defaults: Map<*, *>): Map<String, Any?> {
        val actor = command.toChangeActor()
        val targetMandate = when {
            command.requestedRule != null -> command.toTargetMandateId()?.value?.toString()
            command.targetMandateId != null -> command.targetMandateId.toString()
            else -> defaults["mandateId"]
        }
        return mapOf(
            "tenantId" to command.tenantId.toString(),
            "changeType" to command.changeType.name,
            "requestedBy" to mapOf(
                "actorType" to command.requestedBy.actorType.name,
                "partyProfileId" to command.requestedBy.partyProfileId?.toString(),
            ),
            "requesterPartyProfileId" to command.toRequesterPartyProfileId(actor)?.value?.toString(),
            "requestChannelId" to command.requestChannelId?.toString(),
            "authorityDelegationId" to command.authorityDelegationId?.toString(),
            "targetOwnerPartyProfileId" to command.targetOwnerPartyProfileId.toString(),
            "targetMandateId" to targetMandate,
            "limitRuleId" to command.limitRuleId?.toString(),
            "expectedVersion" to command.expectedVersion,
            "requestedRule" to command.requestedRule?.normalizedIntent(defaults),
            "effectiveAt" to command.effectiveAt?.toString(),
            "expiresAt" to command.expiresAt?.toString(),
            "policyContext" to command.policyContext,
            "metadata" to command.metadata,
        )
    }
}
