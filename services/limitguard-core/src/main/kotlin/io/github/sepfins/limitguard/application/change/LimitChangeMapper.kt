package io.github.sepfins.limitguard.application.change

import io.github.sepfins.limitguard.api.LimitChangeActorType
import io.github.sepfins.limitguard.api.LimitChannelScope as ApiLimitChannelScope
import io.github.sepfins.limitguard.api.LimitKind as ApiLimitKind
import io.github.sepfins.limitguard.api.LimitMandateScope
import io.github.sepfins.limitguard.api.LimitRuleChangeStatus as ApiLimitRuleChangeStatus
import io.github.sepfins.limitguard.api.LimitRuleChangeType as ApiLimitRuleChangeType
import io.github.sepfins.limitguard.api.commands.LimitRuleDraft
import io.github.sepfins.limitguard.api.commands.LimitChangeActorCommand
import io.github.sepfins.limitguard.api.commands.RequestLimitRuleChangeCommand
import io.github.sepfins.limitguard.api.results.LimitChangeRequestResult
import io.github.sepfins.limitguard.api.results.LimitChangeActorResult
import io.github.sepfins.limitguard.domain.change.ChangeActor
import io.github.sepfins.limitguard.domain.change.LimitRuleChangeRequest
import io.github.sepfins.limitguard.domain.change.RequestActorContext
import io.github.sepfins.limitguard.domain.common.ChannelGroupId
import io.github.sepfins.limitguard.domain.common.ChannelId
import io.github.sepfins.limitguard.domain.common.CurrencyCode
import io.github.sepfins.limitguard.domain.common.MandateId
import io.github.sepfins.limitguard.domain.common.Money
import io.github.sepfins.limitguard.domain.common.OperationTypeId
import io.github.sepfins.limitguard.domain.common.PartyProfileId
import io.github.sepfins.limitguard.domain.limit.LimitKind as DomainLimitKind
import io.github.sepfins.limitguard.domain.limit.LimitChannelScope as DomainLimitChannelScope
import io.github.sepfins.limitguard.domain.limit.LimitOperationScope
import io.github.sepfins.limitguard.domain.limit.LimitConfigurationFundingScope
import io.github.sepfins.limitguard.domain.limit.LimitPeriod
import io.github.sepfins.limitguard.domain.limit.LimitRule
import io.github.sepfins.limitguard.domain.limit.LimitRuleTargetScope
import io.github.sepfins.limitguard.domain.limit.LimitRuleName
import io.github.sepfins.limitguard.domain.mandate.MandateScope
import io.github.sepfins.limitguard.internal.json.JsonValueEquality

internal fun LimitRuleDraft.matches(rule: LimitRule): Boolean =
    LimitRuleName.of(name) == rule.name &&
        description == rule.description &&
        toLimitKind() == rule.limitKind &&
        Money.positive(amount, CurrencyCode.of(currency)) == rule.amount &&
        toMandateScope() == rule.mandateScope &&
        toChannelScope() == rule.channelScope &&
        toOperationScope() == rule.operationScope &&
        usageConsumptionMode.name == rule.usageConsumptionMode.name &&
        priority == rule.priority.value &&
        endsAt == rule.effectiveWindow.endsAt &&
        // Omitted values are resolved during initial submission, not again on replay.
        (configurationId == null || configurationId == rule.configurationId?.value) &&
        (startsAt == null || startsAt == rule.effectiveWindow.startsAt) &&
        JsonValueEquality.objectsEqual(metadata, rule.metadata.value)

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

internal fun LimitRuleDraft.toMandateScope(): MandateScope =
    when (mandateScope) {
        LimitMandateScope.ALL_OWNER_MANDATES -> {
            require(mandateId == null) {
                "LimitRuleDraft.mandateId must be null for LimitMandateScope.ALL_OWNER_MANDATES"
            }
            MandateScope.AllOwnerMandates
        }
        LimitMandateScope.MANDATE ->
            MandateScope.MandateSpecific(MandateId.of(
                requireNotNull(mandateId) {
                    "LimitRuleDraft.mandateId is required for LimitMandateScope.MANDATE"
                }
            ))
    }

internal fun LimitRuleDraft.toChannelScope(): DomainLimitChannelScope =
    when (channelScope) {
        ApiLimitChannelScope.ALL_CHANNELS -> {
            require(channelId == null && channelGroupId == null) {
                "LimitRuleDraft channel fields must be null for LimitChannelScope.ALL_CHANNELS"
            }
            DomainLimitChannelScope.AllChannels
        }
        ApiLimitChannelScope.CHANNEL_GROUP -> {
            require(channelId == null) {
                "LimitRuleDraft.channelId must be null for LimitChannelScope.CHANNEL_GROUP"
            }
            DomainLimitChannelScope.ChannelGroupSpecific(ChannelGroupId.of(
                requireNotNull(channelGroupId) {
                    "LimitRuleDraft.channelGroupId is required for LimitChannelScope.CHANNEL_GROUP"
                }
            ))
        }
        ApiLimitChannelScope.CHANNEL -> {
            require(channelGroupId == null) {
                "LimitRuleDraft.channelGroupId must be null for LimitChannelScope.CHANNEL"
            }
            DomainLimitChannelScope.ChannelSpecific(ChannelId.of(
                requireNotNull(channelId) {
                    "LimitRuleDraft.channelId is required for LimitChannelScope.CHANNEL"
                }
            ))
        }
    }

internal fun LimitRuleDraft.toOperationScope(): LimitOperationScope =
    if (operationTypeId == null) LimitOperationScope.AllOperations
    else LimitOperationScope.OperationTypeSpecific(OperationTypeId.of(operationTypeId))

internal fun LimitRuleDraft.toLimitKind(): DomainLimitKind =
    when (limitKind) {
        ApiLimitKind.PER_TRANSACTION -> {
            require(period == null) {
                "LimitRuleDraft.period must be null for LimitKind.PER_TRANSACTION"
            }
            DomainLimitKind.PerTransaction
        }
        ApiLimitKind.PERIODIC -> DomainLimitKind.Periodic(LimitPeriod.valueOf(
            requireNotNull(period?.name) {
                "LimitRuleDraft.period is required for LimitKind.PERIODIC"
            }
        ))
    }

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

internal fun RequestLimitRuleChangeCommand.toTargetMandateId(): MandateId? {
    val draft = requireNotNull(requestedRule) { "requestedRule is required for ${changeType.name}" }
    return when (draft.mandateScope) {
        LimitMandateScope.ALL_OWNER_MANDATES -> {
            require(targetMandateId == null) {
                "RequestLimitRuleChangeCommand.targetMandateId must be null when requestedRule.mandateScope is ALL_OWNER_MANDATES"
            }
            null
        }
        LimitMandateScope.MANDATE -> {
            val draftMandateId = requireNotNull(draft.mandateId) {
                "LimitRuleDraft.mandateId is required for LimitMandateScope.MANDATE"
            }
            require(targetMandateId == null || targetMandateId == draftMandateId) {
                "RequestLimitRuleChangeCommand.targetMandateId must match requestedRule.mandateId"
            }
            MandateId.of(draftMandateId)
        }
    }
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
