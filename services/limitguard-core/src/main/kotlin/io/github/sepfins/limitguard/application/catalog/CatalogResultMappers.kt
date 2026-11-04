package io.github.sepfins.limitguard.application.catalog

import io.github.sepfins.limitguard.internal.json.JsonSnapshots
import io.github.sepfins.limitguard.api.FundingSourceCategory as ApiFundingSourceCategory
import io.github.sepfins.limitguard.api.FundingSourceOwnerRole as ApiFundingSourceOwnerRole
import io.github.sepfins.limitguard.api.LimitChannelScope as ApiLimitChannelScope
import io.github.sepfins.limitguard.api.LimitUsageConsumptionMode as ApiLimitUsageConsumptionMode
import io.github.sepfins.limitguard.application.toApi
import io.github.sepfins.limitguard.api.LimitChangePolicyDecision as ApiLimitChangePolicyDecision
import io.github.sepfins.limitguard.api.LimitChangePolicyEngine as ApiLimitChangePolicyEngine
import io.github.sepfins.limitguard.api.LimitChangePolicyPhase as ApiLimitChangePolicyPhase
import io.github.sepfins.limitguard.api.LimitConfigurationFundingScope as ApiLimitConfigurationFundingScope
import io.github.sepfins.limitguard.api.LimitFundingScope as ApiLimitFundingScope
import io.github.sepfins.limitguard.api.LimitKind as ApiLimitKind
import io.github.sepfins.limitguard.api.LimitPeriod as ApiLimitPeriod
import io.github.sepfins.limitguard.api.ProductNodeType as ApiProductNodeType
import io.github.sepfins.limitguard.api.PartyProfileDelegationType as ApiPartyProfileDelegationType
import io.github.sepfins.limitguard.api.results.ChannelGroupMemberResult
import io.github.sepfins.limitguard.api.results.ChannelGroupResult
import io.github.sepfins.limitguard.api.results.ChannelResult
import io.github.sepfins.limitguard.api.results.EntityIdentifierResult
import io.github.sepfins.limitguard.api.results.FundingSourceGroupMemberResult
import io.github.sepfins.limitguard.api.results.FundingSourceGroupResult
import io.github.sepfins.limitguard.api.results.FundingSourceOwnerResult
import io.github.sepfins.limitguard.api.results.FundingSourceProductResult
import io.github.sepfins.limitguard.api.results.FundingSourceResult
import io.github.sepfins.limitguard.api.results.IdentifierSchemeResult
import io.github.sepfins.limitguard.api.results.LimitChangePolicyResult
import io.github.sepfins.limitguard.api.results.LimitConfigurationResult
import io.github.sepfins.limitguard.api.results.MandateResult
import io.github.sepfins.limitguard.api.results.OperationTypeResult
import io.github.sepfins.limitguard.api.results.PartyProfileDelegationResult
import io.github.sepfins.limitguard.api.results.PartyProfileResult
import io.github.sepfins.limitguard.api.results.TenantResult
import io.github.sepfins.limitguard.api.NoMatchingRuleBehavior
import io.github.sepfins.limitguard.domain.channel.Channel
import io.github.sepfins.limitguard.domain.channel.ChannelGroup
import io.github.sepfins.limitguard.domain.channel.ChannelGroupMember
import io.github.sepfins.limitguard.domain.change.LimitChangePolicy
import io.github.sepfins.limitguard.domain.funding.FundingSourceGroup
import io.github.sepfins.limitguard.domain.funding.FundingSourceGroupMember
import io.github.sepfins.limitguard.domain.funding.FundingSource
import io.github.sepfins.limitguard.domain.funding.FundingSourceOwner
import io.github.sepfins.limitguard.domain.funding.FundingSourceProduct
import io.github.sepfins.limitguard.domain.funding.FundingSourceScope
import io.github.sepfins.limitguard.domain.identifier.EntityIdentifier
import io.github.sepfins.limitguard.domain.identifier.IdentifierScheme
import io.github.sepfins.limitguard.domain.limit.LimitChannelScope as DomainLimitChannelScope
import io.github.sepfins.limitguard.domain.limit.LimitConfiguration
import io.github.sepfins.limitguard.domain.limit.LimitConfigurationFundingScope as DomainLimitConfigurationFundingScope
import io.github.sepfins.limitguard.domain.limit.LimitKind as DomainLimitKind
import io.github.sepfins.limitguard.domain.limit.LimitOperationScope
import io.github.sepfins.limitguard.domain.mandate.Mandate
import io.github.sepfins.limitguard.domain.operation.OperationType
import io.github.sepfins.limitguard.domain.operation.OperationTypeScope
import io.github.sepfins.limitguard.domain.party.PartyProfile
import io.github.sepfins.limitguard.domain.party.PartyProfileDelegation
import io.github.sepfins.limitguard.domain.tenant.Tenant
import java.util.UUID

internal fun Tenant.toResult(): TenantResult =
    TenantResult(
        id = id.value,
        name = name.value,
        timeZone = timeZone,
        config = config.value,
        noMatchingRuleBehavior = NoMatchingRuleBehavior.valueOf(noMatchingRuleBehavior.name),
        createdAt = requireNotNull(createdAt) {
            "Tenant.createdAt is required when mapping to TenantResult"
        },
    )

internal fun IdentifierScheme.toResult(): IdentifierSchemeResult =
    IdentifierSchemeResult(
        id = id.value,
        tenantId = tenantId.value,
        entityType = entityType.value,
        schemeName = schemeName.value,
        formatRegex = formatRegex,
        maxLength = maxLength,
        isPrimary = isPrimary,
        createdAt = requireNotNull(createdAt) {
            "IdentifierScheme.createdAt is required when mapping to IdentifierSchemeResult"
        },
    )

internal fun EntityIdentifier.toResult(scheme: IdentifierScheme): EntityIdentifierResult =
    EntityIdentifierResult(
        id = id.value,
        tenantId = scheme.tenantId.value,
        entityType = entityType.value,
        entityId = entityId,
        schemeId = schemeId.value,
        schemeName = scheme.schemeName.value,
        identifier = identifier.value,
        createdAt = requireNotNull(createdAt) {
            "EntityIdentifier.createdAt is required when mapping to EntityIdentifierResult"
        },
    )

internal fun Channel.toResult(): ChannelResult =
    ChannelResult(
        id = id.value,
        tenantId = tenantId.value,
        code = code.value,
        name = name.value,
        isActive = isActive,
        metadata = metadata.value,
        createdAt = requireNotNull(createdAt) {
            "Channel.createdAt is required when mapping to ChannelResult"
        },
    )

internal fun ChannelGroup.toResult(memberChannelIds: List<UUID>): ChannelGroupResult =
    ChannelGroupResult(
        id = id.value,
        tenantId = tenantId.value,
        code = code.value,
        name = name.value,
        metadata = metadata.value,
        createdAt = requireNotNull(createdAt) {
            "ChannelGroup.createdAt is required when mapping to ChannelGroupResult"
        },
        memberChannelIds = memberChannelIds,
    )

internal fun ChannelGroupMember.toResult(): ChannelGroupMemberResult =
    ChannelGroupMemberResult(
        tenantId = tenantId.value,
        channelGroupId = groupId.value,
        channelId = channelId.value,
        createdAt = requireNotNull(createdAt) {
            "ChannelGroupMember.createdAt is required when mapping to ChannelGroupMemberResult"
        },
    )

internal fun OperationType.toResult(): OperationTypeResult =
    OperationTypeResult(
        id = id.value,
        tenantId = if (scope is OperationTypeScope.TenantSpecific) scope.tenantId.value else null,
        parentId = parentId?.value,
        code = code.value,
        name = name.value,
        isLeaf = isLeaf,
        isGlobal = scope is OperationTypeScope.Global,
        createdAt = requireNotNull(createdAt) {
            "Operation.createdAt is required when mapping to OperationTypeResult"
        }
    )

internal fun PartyProfile.toResult(
    externalIdentifiers: List<EntityIdentifierResult> = emptyList(),
): PartyProfileResult =
    PartyProfileResult(
        id = id.value,
        tenantId = tenantId.value,
        partyId = partyId.value,
        externalIdentifiers = externalIdentifiers,
        createdAt = requireNotNull(createdAt) {
            "PartyProfile.createdAt is required when mapping to PartyProfileResult"
        },
        metadata = metadata.value,
    )

internal fun PartyProfileDelegation.toResult(): PartyProfileDelegationResult =
    PartyProfileDelegationResult(
        id = id.value,
        tenantId = tenantId.value,
        ownerPartyProfileId = ownerPartyProfileId.value,
        delegatePartyProfileId = delegatePartyProfileId.value,
        delegationType = ApiPartyProfileDelegationType.valueOf(delegationType.name),
        startsAt = effectiveWindow.startsAt,
        endsAt = effectiveWindow.endsAt,
        isActive = isActive,
        metadata = metadata.value,
        createdAt = requireNotNull(createdAt) {
            "PartyProfileDelegation.createdAt is required when mapping to PartyProfileDelegationResult"
        },
    )

internal fun FundingSourceProduct.toResult(): FundingSourceProductResult =
    FundingSourceProductResult(
        id = id.value,
        tenantId = tenantId.value,
        parentId = parentId?.value,
        category = ApiFundingSourceCategory.valueOf(accountCategory.name),
        nodeType = ApiProductNodeType.valueOf(nodeType.name),
        code = code.value,
        name = name.value,
        isLeaf = isLeaf,
        isActive = isActive,
        metadata = metadata.value,
        createdAt = requireNotNull(createdAt) {
            "FundingSourceProduct.createdAt is required when mapping to FundingSourceProductResult"
        }
    )

internal fun FundingSource.toResult(): FundingSourceResult =
    FundingSourceResult(
        id = id.value,
        tenantId = tenantId.value,
        productId = productId.value,
        externalReference = externalReference?.value,
        isActive = isActive,
        metadata = metadata.value,
        createdAt = requireNotNull(createdAt) {
            "FundingSource.createdAt is required when mapping to FundingSourceResult"
        }
    )

internal fun FundingSourceOwner.toResult(): FundingSourceOwnerResult =
    FundingSourceOwnerResult(
        id = id.value,
        tenantId = tenantId.value,
        partyProfileId = partyProfileId.value,
        fundingSourceId = fundingSourceId.value,
        ownerRole = ApiFundingSourceOwnerRole.valueOf(ownerRole.name),
        createdAt = requireNotNull(createdAt) {
            "FundingSourceOwner.createdAt is required when mapping to FundingSourceOwnerResult"
        },
    )

internal fun FundingSourceGroup.toResult(): FundingSourceGroupResult =
    FundingSourceGroupResult(
        id = id.value,
        tenantId = tenantId.value,
        ownerPartyProfileId = ownerPartyProfileId.value,
        code = code.value,
        name = name.value,
        metadata = metadata.value,
        createdAt = requireNotNull(createdAt) {
            "FundingSourceGroup.createdAt is required when mapping to FundingSourceGroupResult"
        },
    )

internal fun FundingSourceGroupMember.toResult(): FundingSourceGroupMemberResult =
    FundingSourceGroupMemberResult(
        tenantId = tenantId.value,
        fundingSourceGroupId = groupId.value,
        ownerPartyProfileId = ownerPartyProfileId.value,
        fundingSourceId = fundingSourceId.value,
        createdAt = requireNotNull(createdAt) {
            "FundingSourceGroupMember.createdAt is required when mapping to FundingSourceGroupMemberResult"
        },
    )

internal fun Mandate.toResult(): MandateResult {
    val createdAt = requireNotNull(createdAt) {
        "Mandate.createdAt is required when mapping to MandateResult"
    }

    return MandateResult(
        id = id.value,
        tenantId = tenantId.value,
        ownerPartyProfileId = ownerPartyProfileId.value,
        assigneePartyProfileId = assigneePartyProfileId.value,
        authorityDelegationId = authorityDelegationId?.value,
        name = name.value,
        description = description,
        fundingScope = fundingScope.toApiFundingScope(),
        fundingSourceId = when (val scope = fundingScope) {
            FundingSourceScope.AllOwnerSources -> null
            is FundingSourceScope.FundingSourceSpecific -> scope.fundingSourceId.value
            is FundingSourceScope.FundingSourceGroupSpecific -> null
        },
        fundingSourceGroupId = when (val scope = fundingScope) {
            FundingSourceScope.AllOwnerSources -> null
            is FundingSourceScope.FundingSourceSpecific -> null
            is FundingSourceScope.FundingSourceGroupSpecific -> scope.fundingSourceGroupId.value
        },
        isActive = isActive,
        version = version.value,
        startsAt = effectiveWindow.startsAt,
        endsAt = effectiveWindow.endsAt,
        metadata = metadata.value,
        createdAt = createdAt,
        updatedAt = updatedAt ?: createdAt,
    )
}

internal fun LimitConfiguration.toResult(): LimitConfigurationResult {
    val createdAt = requireNotNull(createdAt) {
        "LimitConfiguration.createdAt is required when mapping to LimitConfigurationResult"
    }

    return LimitConfigurationResult(
        id = id.value,
        tenantId = tenantId.value,
        parentConfigurationId = parentConfigurationId?.value,
        code = code.value,
        name = name.value,
        description = description,
        limitKind = limitKind.toApiLimitKind(),
        period = when (val kind = limitKind) {
            DomainLimitKind.PerTransaction -> null
            is DomainLimitKind.Periodic -> ApiLimitPeriod.valueOf(kind.period.name)
        },
        currency = currency.value,
        fundingScope = fundingScope.toApiFundingScope(),
        fundingSourceCategory = when (val scope = fundingScope) {
            DomainLimitConfigurationFundingScope.AllFundingSources -> null
            is DomainLimitConfigurationFundingScope.FundingSourceCategorySpecific ->
                ApiFundingSourceCategory.valueOf(scope.sourceCategory.name)
            is DomainLimitConfigurationFundingScope.FundingSourceProductSpecific -> null
        },
        fundingSourceProductId = when (val scope = fundingScope) {
            DomainLimitConfigurationFundingScope.AllFundingSources -> null
            is DomainLimitConfigurationFundingScope.FundingSourceCategorySpecific -> null
            is DomainLimitConfigurationFundingScope.FundingSourceProductSpecific -> scope.productId.value
        },
        channelScope = channelScope.toApiChannelScope(),
        channelId = when (val scope = channelScope) {
            DomainLimitChannelScope.AllChannels -> null
            is DomainLimitChannelScope.ChannelSpecific -> scope.channelId.value
            is DomainLimitChannelScope.ChannelGroupSpecific -> null
        },
        channelGroupId = when (val scope = channelScope) {
            DomainLimitChannelScope.AllChannels -> null
            is DomainLimitChannelScope.ChannelSpecific -> null
            is DomainLimitChannelScope.ChannelGroupSpecific -> scope.channelGroupId.value
        },
        operationTypeId = when (val scope = operationScope) {
            LimitOperationScope.AllOperations -> null
            is LimitOperationScope.OperationTypeSpecific -> scope.operationTypeId.value
        },
        maxAssignableAmount = maxAssignableAmount.toApi(),
        defaultAmount = defaultAmount?.amount,
        priority = priority.value,
        usageConsumptionMode = ApiLimitUsageConsumptionMode.valueOf(usageConsumptionMode.name),
        isActive = isActive,
        startsAt = effectiveWindow.startsAt,
        endsAt = effectiveWindow.endsAt,
        metadata = metadata.value,
        createdAt = createdAt,
        updatedAt = updatedAt ?: createdAt,
    )
}

internal fun LimitChangePolicy.toResult(): LimitChangePolicyResult {
    val createdAt = requireNotNull(createdAt) {
        "LimitChangePolicy.createdAt is required when mapping to LimitChangePolicyResult"
    }

    return LimitChangePolicyResult(
        id = id.value,
        tenantId = tenantId.value,
        code = code.value,
        name = name.value,
        description = description,
        phase = ApiLimitChangePolicyPhase.valueOf(phase.name),
        engine = ApiLimitChangePolicyEngine.valueOf(engine.name),
        decision = ApiLimitChangePolicyDecision.valueOf(decision.name),
        limitConfigurationId = limitConfigurationId?.value,
        priority = priority.value,
        isActive = isActive,
        appliesTo = appliesTo.value,
        conditionExpression = conditionExpression.value,
        actionExpression = JsonSnapshots.objectList(actionExpression, "policy.actionExpression"),
        denialCode = denialCode,
        denialMessage = denialMessage,
        startsAt = effectiveWindow.startsAt,
        endsAt = effectiveWindow.endsAt,
        metadata = metadata.value,
        createdAt = createdAt,
        updatedAt = updatedAt ?: createdAt,
    )
}

private fun FundingSourceScope.toApiFundingScope(): ApiLimitFundingScope =
    when (this) {
        FundingSourceScope.AllOwnerSources -> ApiLimitFundingScope.ALL_OWNER_SOURCES
        is FundingSourceScope.FundingSourceSpecific -> ApiLimitFundingScope.FUNDING_SOURCE
        is FundingSourceScope.FundingSourceGroupSpecific -> ApiLimitFundingScope.FUNDING_SOURCE_GROUP
    }

private fun DomainLimitConfigurationFundingScope.toApiFundingScope(): ApiLimitConfigurationFundingScope =
    when (this) {
        DomainLimitConfigurationFundingScope.AllFundingSources ->
            ApiLimitConfigurationFundingScope.ALL_FUNDING_SOURCES
        is DomainLimitConfigurationFundingScope.FundingSourceCategorySpecific ->
            ApiLimitConfigurationFundingScope.FUNDING_SOURCE_CATEGORY
        is DomainLimitConfigurationFundingScope.FundingSourceProductSpecific ->
            ApiLimitConfigurationFundingScope.FUNDING_SOURCE_PRODUCT
    }

private fun DomainLimitChannelScope.toApiChannelScope(): ApiLimitChannelScope =
    when (this) {
        DomainLimitChannelScope.AllChannels -> ApiLimitChannelScope.ALL_CHANNELS
        is DomainLimitChannelScope.ChannelSpecific -> ApiLimitChannelScope.CHANNEL
        is DomainLimitChannelScope.ChannelGroupSpecific -> ApiLimitChannelScope.CHANNEL_GROUP
    }

private fun DomainLimitKind.toApiLimitKind(): ApiLimitKind =
    when (this) {
        DomainLimitKind.PerTransaction -> ApiLimitKind.PER_TRANSACTION
        is DomainLimitKind.Periodic -> ApiLimitKind.PERIODIC
    }
