package io.github.sepfins.limitguard.application.change

import io.github.sepfins.limitguard.api.commands.RequestLimitRuleChangeCommand
import io.github.sepfins.limitguard.domain.change.LimitRuleChangeRequest
import io.github.sepfins.limitguard.domain.change.LimitRuleChangeStatus
import io.github.sepfins.limitguard.domain.change.LimitRuleChangeType
import io.github.sepfins.limitguard.domain.change.ScopeHash
import io.github.sepfins.limitguard.domain.common.IdempotencyKey
import io.github.sepfins.limitguard.domain.common.LimitChangeRequestId
import io.github.sepfins.limitguard.domain.common.Metadata
import io.github.sepfins.limitguard.domain.common.PartyProfileDelegationId
import io.github.sepfins.limitguard.domain.limit.LimitRule
import io.github.sepfins.limitguard.internal.hashing.StableJsonHasher
import io.github.sepfins.limitguard.internal.hashing.TargetScopeCanonicalizer
import io.github.sepfins.limitguard.port.ids.IdGenerator
import java.time.Instant

internal class LimitChangeRequestFactory(
    private val idGenerator: IdGenerator,
) {
    fun create(
        command: RequestLimitRuleChangeCommand,
        requestedRule: LimitRule,
        now: Instant
    ): LimitRuleChangeRequest {

        val targetScope = requestedRule.toTargetScope()
        val targetScopeSnapshot = LimitRuleSnapshotter.targetScopeSnapshot(targetScope)
        val requestedRuleSnapshot = LimitRuleSnapshotter.ruleSnapshot(requestedRule)

        val targetScopeCanonical = TargetScopeCanonicalizer.canonicalize(targetScope)
        val targetScopeHash = StableJsonHasher.sha256Hex(targetScopeCanonical)
        val actor = command.toChangeActor()

        return LimitRuleChangeRequest(
            id = LimitChangeRequestId.of(idGenerator.newId()),
            tenantId = requestedRule.tenantId,
            changeType = LimitRuleChangeType.CREATE,
            status = LimitRuleChangeStatus.REQUESTED,
            requestedBy = actor,
            targetOwnerPartyProfileId = requestedRule.ownerPartyProfileId,
            targetMandateId = command.toTargetMandateId(),
            targetScope = targetScopeSnapshot,
            targetScopeHash = ScopeHash.of(targetScopeHash),
            requestedRule = requestedRuleSnapshot,
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
