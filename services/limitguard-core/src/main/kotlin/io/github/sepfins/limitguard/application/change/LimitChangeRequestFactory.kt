package io.github.sepfins.limitguard.application.change

import io.github.sepfins.limitguard.api.commands.RequestLimitRuleChangeCommand
import io.github.sepfins.limitguard.domain.change.LimitRuleChangeRequest
import io.github.sepfins.limitguard.domain.change.LimitRuleChangeStatus
import io.github.sepfins.limitguard.domain.change.LimitRuleChangeType
import io.github.sepfins.limitguard.domain.change.ScopeHash
import io.github.sepfins.limitguard.domain.common.IdempotencyKey
import io.github.sepfins.limitguard.domain.common.LimitChangeRequestId
import io.github.sepfins.limitguard.domain.common.MandateId
import io.github.sepfins.limitguard.domain.common.Metadata
import io.github.sepfins.limitguard.domain.common.PartyProfileDelegationId
import io.github.sepfins.limitguard.domain.limit.LimitRule
import io.github.sepfins.limitguard.domain.mandate.MandateScope
import io.github.sepfins.limitguard.internal.hashing.StableJsonHasher
import io.github.sepfins.limitguard.internal.hashing.TargetScopeCanonicalizer
import java.time.Instant

internal class LimitChangeRequestFactory {
    fun create(
        id: LimitChangeRequestId,
        command: RequestLimitRuleChangeCommand,
        requestedRule: LimitRule,
        currentRule: LimitRule?,
        now: Instant
    ): LimitRuleChangeRequest {

        val targetScope = requestedRule.toTargetScope()
        val targetScopeSnapshot = LimitRuleSnapshotter.targetScopeSnapshot(targetScope)
        val requestedRuleSnapshot = LimitRuleSnapshotter.ruleSnapshot(requestedRule)

        val targetScopeCanonical = TargetScopeCanonicalizer.canonicalize(targetScope)
        val targetScopeHash = StableJsonHasher.sha256Hex(targetScopeCanonical)
        val actor = command.toChangeActor()

        return LimitRuleChangeRequest(
            id = id,
            tenantId = requestedRule.tenantId,
            changeType = LimitRuleChangeType.valueOf(command.changeType.name),
            status = LimitRuleChangeStatus.REQUESTED,
            requestedBy = actor,
            limitRuleId = currentRule?.id,
            targetOwnerPartyProfileId = requestedRule.ownerPartyProfileId,
            targetMandateId = requestedRule.targetMandateId(),
            targetScope = targetScopeSnapshot,
            targetScopeHash = ScopeHash.of(targetScopeHash),
            requestedRule = requestedRuleSnapshot,
            beforeRule = currentRule?.let { LimitRuleSnapshotter.ruleSnapshot(it) },
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

    private fun LimitRule.targetMandateId(): MandateId? =
        when (val scope = mandateScope) {
            MandateScope.AllOwnerMandates -> null
            is MandateScope.MandateSpecific -> scope.mandateId
        }
}
