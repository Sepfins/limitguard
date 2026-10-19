package io.github.sepfins.limitguard.application.change

import io.github.sepfins.limitguard.api.commands.RequestLimitRuleChangeCommand
import io.github.sepfins.limitguard.domain.change.LimitRuleChangeRequest
import io.github.sepfins.limitguard.domain.change.LimitRuleChangeStatus
import io.github.sepfins.limitguard.domain.change.LimitRuleChangeType
import io.github.sepfins.limitguard.domain.change.ResolvedLimitChangeFacts
import io.github.sepfins.limitguard.domain.common.IdempotencyKey
import io.github.sepfins.limitguard.domain.common.LimitChangeRequestId
import io.github.sepfins.limitguard.domain.common.MandateId
import io.github.sepfins.limitguard.domain.common.Metadata
import io.github.sepfins.limitguard.domain.common.PartyProfileDelegationId
import io.github.sepfins.limitguard.domain.common.PartyProfileId
import io.github.sepfins.limitguard.domain.common.TenantId
import io.github.sepfins.limitguard.domain.limit.LimitAmount
import java.time.Instant

internal class LimitChangeRequestFactory {
    fun create(
        id: LimitChangeRequestId,
        command: RequestLimitRuleChangeCommand,
        changeType: LimitRuleChangeType,
        requestedAmount: LimitAmount,
        facts: ResolvedLimitChangeFacts,
        now: Instant
    ): LimitRuleChangeRequest {
        val actor = command.toChangeActor()

        return LimitRuleChangeRequest(
            id = id,
            tenantId = TenantId.of(command.tenantId),
            changeType = changeType,
            status = LimitRuleChangeStatus.REQUESTED,
            requestedBy = actor,
            limitRuleId = (facts.currentRule ?: facts.deletedRule)?.id,
            targetOwnerPartyProfileId = PartyProfileId.of(command.target.ownerPartyProfileId),
            targetMandateId = command.target.mandateId?.let { MandateId.of(it) },
            configurationId = facts.configuration.id,
            requestedAmount = requestedAmount,
            beforeAmount = facts.currentRule?.amount,
            authorityDelegationId = command.authorityDelegationId?.let { PartyProfileDelegationId.of(it) },
            requestActorContext = command.toRequestActorContext(actor),
            requesterPartyProfileId = command.toRequesterPartyProfileId(actor),
            policyContext = Metadata(command.policyContext),
            effectiveAt = command.effectiveAt,
            expiresAt = command.expiresAt,
            idempotencyKey = command.idempotencyKey?.let { IdempotencyKey.of(it) },
            metadata = Metadata(command.metadata),
            createdAt = now,
            updatedAt = now
        )
    }
}
