package io.github.sepfins.limitguard.application.change

import io.github.sepfins.limitguard.api.LimitAmount
import io.github.sepfins.limitguard.api.commands.LimitRuleChangeOperation
import io.github.sepfins.limitguard.api.commands.RequestLimitRuleChangeCommand
import io.github.sepfins.limitguard.api.errors.InfrastructureException
import io.github.sepfins.limitguard.domain.common.Metadata
import io.github.sepfins.limitguard.internal.json.JsonValueEquality

/** The submission as the host sent it, so a replay is compared with what was asked, not with what core resolved. */
internal object LimitChangeCreationIntent {
    fun capture(command: RequestLimitRuleChangeCommand): Metadata =
        Metadata(mapOf("submission" to normalize(command)))

    fun matches(intent: Map<*, *>, command: RequestLimitRuleChangeCommand): Boolean {
        val submission = intent["submission"] as? Map<*, *>
            ?: throw InfrastructureException("original creation intent is missing its submission")
        return JsonValueEquality.valuesEqual(submission, normalize(command))
    }

    private fun normalize(command: RequestLimitRuleChangeCommand): Map<String, Any?> {
        val actor = command.toChangeActor()
        return mapOf(
            "tenantId" to command.tenantId.toString(),
            "requestedBy" to mapOf(
                "actorType" to command.requestedBy.actorType.name,
                "partyProfileId" to command.requestedBy.partyProfileId?.toString(),
            ),
            "requesterPartyProfileId" to command.toRequesterPartyProfileId(actor)?.value?.toString(),
            "requestChannelId" to command.requestChannelId?.toString(),
            "authorityDelegationId" to command.authorityDelegationId?.toString(),
            "target" to mapOf(
                "ownerPartyProfileId" to command.target.ownerPartyProfileId.toString(),
                "configurationId" to command.target.configurationId.toString(),
                "mandateId" to command.target.mandateId?.toString(),
            ),
            "operation" to normalize(command.operation),
            "expectedVersion" to command.expectedVersion,
            "effectiveAt" to command.effectiveAt?.toString(),
            "expiresAt" to command.expiresAt?.toString(),
            "policyContext" to command.policyContext,
            "metadata" to command.metadata,
        )
    }

    private fun normalize(operation: LimitRuleChangeOperation): Map<String, Any?> =
        when (operation) {
            is LimitRuleChangeOperation.SetAmount -> mapOf(
                "type" to "SET_AMOUNT",
                "amount" to when (val amount = operation.amount) {
                    is LimitAmount.Bounded -> amount.value.stripTrailingZeros().toPlainString()
                    LimitAmount.Unlimited -> "UNLIMITED"
                },
            )
            LimitRuleChangeOperation.Deactivate -> mapOf("type" to "DEACTIVATE")
            LimitRuleChangeOperation.Reactivate -> mapOf("type" to "REACTIVATE")
            LimitRuleChangeOperation.Delete -> mapOf("type" to "DELETE")
        }
}
