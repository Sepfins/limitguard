package io.github.sepfins.limitguard.application.change

import io.github.sepfins.limitguard.api.LimitChangeActorType
import io.github.sepfins.limitguard.api.LimitRuleChangeStatus as ApiLimitRuleChangeStatus
import io.github.sepfins.limitguard.api.LimitRuleChangeType as ApiLimitRuleChangeType
import io.github.sepfins.limitguard.api.commands.LimitChangeActorCommand
import io.github.sepfins.limitguard.api.commands.RequestLimitRuleChangeCommand
import io.github.sepfins.limitguard.api.results.LimitChangeRequestResult
import io.github.sepfins.limitguard.api.results.LimitChangeActorResult
import io.github.sepfins.limitguard.domain.change.ChangeActor
import io.github.sepfins.limitguard.domain.change.LimitRuleChangeRequest
import io.github.sepfins.limitguard.domain.change.RequestActorContext
import io.github.sepfins.limitguard.domain.common.ChannelId
import io.github.sepfins.limitguard.domain.common.MandateId
import io.github.sepfins.limitguard.domain.common.PartyProfileId
import io.github.sepfins.limitguard.domain.limit.LimitConfiguration
import io.github.sepfins.limitguard.domain.limit.LimitConfigurationFundingScope
import io.github.sepfins.limitguard.domain.limit.LimitRule
import io.github.sepfins.limitguard.domain.limit.LimitRuleTargetScope
import io.github.sepfins.limitguard.domain.mandate.MandateScope

internal fun LimitRule.toTargetScope(fundingScope: LimitConfigurationFundingScope): LimitRuleTargetScope =
    LimitRuleTargetScope(
        owner = ownerPartyProfileId,
        mandateScope = mandateScope,
        channelScope = channelScope,
        operationScope = operationScope,
        currency = amount.currency,
        kind = limitKind,
        fundingScope = fundingScope,
    )

/** Every rule on a configuration shares the configuration's scope, so a target can be locked before its rule is known. */
internal fun LimitConfiguration.targetScope(owner: PartyProfileId, mandateScope: MandateScope): LimitRuleTargetScope =
    LimitRuleTargetScope(
        owner = owner,
        mandateScope = mandateScope,
        channelScope = channelScope,
        operationScope = operationScope,
        currency = currency,
        kind = limitKind,
        fundingScope = fundingScope,
    )

internal fun RequestLimitRuleChangeCommand.toMandateScope(): MandateScope =
    target.mandateId?.let { MandateScope.MandateSpecific(MandateId.of(it)) } ?: MandateScope.AllOwnerMandates

internal fun RequestLimitRuleChangeCommand.toChangeActor(): ChangeActor =
    requestedBy.toChangeActor()

internal fun LimitChangeActorCommand.toChangeActor(): ChangeActor =
    when (actorType) {
        LimitChangeActorType.SYSTEM ->
            ChangeActor.System.also {
                require(partyProfileId == null) {
                    "LimitChangeActorCommand.partyProfileId must be null for LimitChangeActorType.SYSTEM"
                }
            }
        LimitChangeActorType.PARTY_PROFILE ->
            ChangeActor.PartyProfileActor(PartyProfileId.of(
                requireNotNull(partyProfileId) {
                    "LimitChangeActorCommand.partyProfileId is required for LimitChangeActorType.PARTY_PROFILE"
                }
            ))
    }

internal fun RequestLimitRuleChangeCommand.toRequestActorContext(actor: ChangeActor): RequestActorContext =
    when (actor) {
        ChangeActor.System -> RequestActorContext.System(requestChannelId?.let(ChannelId::of))
        is ChangeActor.PartyProfileActor ->
            RequestActorContext.PartyProfile(
                actorPartyProfileId = actor.partyProfileId,
                requestChannelId = ChannelId.of(
                    requireNotNull(requestChannelId) {
                        "RequestLimitRuleChangeCommand.requestChannelId is required for LimitChangeActorType.PARTY_PROFILE"
                    }
                ),
            )
    }

internal fun RequestLimitRuleChangeCommand.toRequesterPartyProfileId(actor: ChangeActor): PartyProfileId? =
    requesterPartyProfileId?.let { PartyProfileId.of(it) }
        ?: when (actor) {
            is ChangeActor.PartyProfileActor -> actor.partyProfileId
            ChangeActor.System -> null
        }

internal fun LimitRuleChangeRequest.toResult(): LimitChangeRequestResult =
    LimitChangeRequestResult(
        id = id.value,
        tenantId = tenantId.value,
        limitRuleId = limitRuleId?.value,
        changeType = ApiLimitRuleChangeType.valueOf(changeType.name),
        status = ApiLimitRuleChangeStatus.valueOf(status.name),
        requestedBy = when (val actor = requestedBy) {
            ChangeActor.System -> LimitChangeActorResult(LimitChangeActorType.SYSTEM)
            is ChangeActor.PartyProfileActor -> LimitChangeActorResult(
                LimitChangeActorType.PARTY_PROFILE, partyProfileId = actor.partyProfileId.value,
            )
        },
        requesterPartyProfileId = requesterPartyProfileId?.value,
        requestChannelId = requestActorContext.requestChannelId?.value,
        authorityDelegationId = authorityDelegationId?.value,
        targetOwnerPartyProfileId = targetOwnerPartyProfileId.value,
        targetMandateId = targetMandateId?.value,
        targetScopeHash = targetScopeHash.value,
        effectiveAt = effectiveAt,
        expiresAt = expiresAt,
        decidedAt = decidedAt,
        appliedAt = appliedAt,
        cancelledAt = cancelledAt,
        decisionReason = decisionReason,
        metadata = metadata.value,
        createdAt = requireNotNull(createdAt) {
            "LimitRuleChangeRequest.createdAt is required when mapping to LimitChangeRequestResult"
        },
        updatedAt = updatedAt ?: createdAt,
    )
