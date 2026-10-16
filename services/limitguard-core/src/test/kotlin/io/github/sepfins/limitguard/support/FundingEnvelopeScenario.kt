package io.github.sepfins.limitguard.support

import io.github.sepfins.limitguard.api.LimitAmount
import io.github.sepfins.limitguard.api.FundingSourceCategory
import io.github.sepfins.limitguard.api.LimitChangeActorType
import io.github.sepfins.limitguard.api.LimitChannelScope
import io.github.sepfins.limitguard.api.LimitConfigurationFundingScope
import io.github.sepfins.limitguard.api.LimitKind
import io.github.sepfins.limitguard.api.Limitguard
import io.github.sepfins.limitguard.api.PartyType
import io.github.sepfins.limitguard.api.ProductNodeType
import io.github.sepfins.limitguard.api.TrustedChangeActorContext
import io.github.sepfins.limitguard.api.commands.ApproveLimitRuleChangeCommand
import io.github.sepfins.limitguard.api.commands.AddChannelGroupMemberCommand
import io.github.sepfins.limitguard.api.commands.AssignFundingSourceOwnerCommand
import io.github.sepfins.limitguard.api.commands.AuthorizeTransactionCommand
import io.github.sepfins.limitguard.api.commands.CreateLimitConfigurationCommand
import io.github.sepfins.limitguard.api.commands.CreateChannelGroupCommand
import io.github.sepfins.limitguard.api.commands.LimitChangeActorCommand
import io.github.sepfins.limitguard.api.commands.LimitRuleChangeOperation
import io.github.sepfins.limitguard.api.commands.LimitRuleTargetCommand
import io.github.sepfins.limitguard.api.commands.RegisterChannelCommand
import io.github.sepfins.limitguard.api.commands.RegisterFundingSourceCommand
import io.github.sepfins.limitguard.api.commands.RegisterFundingSourceProductCommand
import io.github.sepfins.limitguard.api.commands.RegisterOperationTypeCommand
import io.github.sepfins.limitguard.api.commands.RegisterPartyProfileCommand
import io.github.sepfins.limitguard.api.commands.RegisterTenantCommand
import io.github.sepfins.limitguard.api.commands.RequestLimitRuleChangeCommand
import io.github.sepfins.limitguard.api.queries.FindFundingSourceProductByCodeQuery
import io.github.sepfins.limitguard.api.queries.FindLimitConfigurationByCodeQuery
import io.github.sepfins.limitguard.api.queries.FindOperationTypesByCodeQuery
import io.github.sepfins.limitguard.api.queries.GetLimitChangeQuery
import io.github.sepfins.limitguard.api.queries.GetLimitRuleQuery
import io.github.sepfins.limitguard.api.results.LimitConfigurationResult
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

class FundingEnvelopeScenario(val facade: Limitguard, val now: Instant) {
    val tenant = facade.tenantCatalogService.registerTenant(
        RegisterTenantCommand("Envelope bank ${UUID.randomUUID()}"),
    ).id
    val owner by lazy {
        facade.tenantCatalogService.registerPartyProfile(RegisterPartyProfileCommand(
            tenant, PartyType.INDIVIDUAL, firstName = "Owner", lastName = "Customer",
        )).id
    }
    val channel by lazy { facade.tenantCatalogService.registerChannel(RegisterChannelCommand(tenant, "API", "API")).id }
    val operation by lazy {
        facade.tenantCatalogService.registerOperationType(RegisterOperationTypeCommand(tenant, "TRANSFER", "Transfer")).id
    }

    fun registerChannel(code: String, active: Boolean = true) = facade.tenantCatalogService.registerChannel(
        RegisterChannelCommand(tenant, code, code, isActive = active),
    )

    fun registerOperation(code: String, parent: UUID? = null, global: Boolean = false, leaf: Boolean = false) =
        facade.tenantCatalogService.registerOperationType(RegisterOperationTypeCommand(
            tenant, code, code, parentId = parent, isGlobal = global, isLeaf = leaf,
        ))

    fun channelGroup(code: String, vararg channels: UUID) = facade.tenantCatalogService.createChannelGroup(
        CreateChannelGroupCommand(tenant, code, code),
    ).also { group -> channels.forEach { addChannelMember(group.id, it) } }

    fun addChannelMember(group: UUID, channel: UUID) = facade.tenantCatalogService.addChannelGroupMember(
        AddChannelGroupMemberCommand(tenant, group, channel),
    )

    fun product(
        code: String,
        parent: UUID? = null,
        category: FundingSourceCategory = FundingSourceCategory.TRANSACTIONAL,
        leaf: Boolean = false,
        active: Boolean = true,
    ) = facade.tenantCatalogService.registerFundingSourceProduct(RegisterFundingSourceProductCommand(
        tenant, code, code, category, if (leaf) ProductNodeType.PRODUCT else ProductNodeType.FAMILY,
        parentId = parent, isLeaf = leaf, isActive = active,
    ))

    fun configurationCommand(
        code: String,
        parent: UUID? = null,
        category: FundingSourceCategory? = null,
        product: UUID? = null,
        active: Boolean = true,
        channelId: UUID? = null,
        channelGroupId: UUID? = null,
        operationId: UUID? = null,
    ) = CreateLimitConfigurationCommand(
        tenantId = tenant, code = code, name = code, parentConfigurationId = parent,
        limitKind = LimitKind.PER_TRANSACTION, currency = "EUR", maxAssignableAmount = LimitAmount.Bounded(BigDecimal("1000")),
        fundingScope = when {
            category != null -> LimitConfigurationFundingScope.FUNDING_SOURCE_CATEGORY
            product != null -> LimitConfigurationFundingScope.FUNDING_SOURCE_PRODUCT
            else -> LimitConfigurationFundingScope.ALL_FUNDING_SOURCES
        },
        fundingSourceCategory = category, fundingSourceProductId = product, isActive = active, startsAt = now,
        channelScope = when {
            channelId != null -> LimitChannelScope.CHANNEL
            channelGroupId != null -> LimitChannelScope.CHANNEL_GROUP
            else -> LimitChannelScope.ALL_CHANNELS
        },
        channelId = channelId, channelGroupId = channelGroupId,
        operationTypeId = operationId,
    )

    fun configuration(
        code: String,
        parent: UUID? = null,
        category: FundingSourceCategory? = null,
        product: UUID? = null,
        active: Boolean = true,
        channelId: UUID? = null,
        channelGroupId: UUID? = null,
        operationId: UUID? = null,
    ) = facade.tenantCatalogService.createLimitConfiguration(configurationCommand(
        code, parent, category, product, active, channelId, channelGroupId, operationId,
    ))

    fun findConfiguration(code: String) = facade.limitInquiryService.findLimitConfigurationByCode(
        FindLimitConfigurationByCodeQuery(tenant, code),
    )

    fun findOperations(code: String) = facade.limitInquiryService.findOperationTypesByCode(
        FindOperationTypesByCodeQuery(tenant, code),
    )

    fun findProduct(code: String) = facade.limitInquiryService.findFundingSourceProductByCode(
        FindFundingSourceProductByCodeQuery(tenant, code),
    )

    fun source(product: UUID): UUID {
        val source = facade.tenantCatalogService.registerFundingSource(RegisterFundingSourceCommand(tenant, product)).id
        facade.tenantCatalogService.assignFundingSourceOwner(AssignFundingSourceOwnerCommand(tenant, owner, source))
        return source
    }

    fun requestCommand(configuration: LimitConfigurationResult, amount: String = "500") = RequestLimitRuleChangeCommand(
        tenantId = tenant,
        requestedBy = LimitChangeActorCommand(LimitChangeActorType.PARTY_PROFILE, partyProfileId = owner),
        target = LimitRuleTargetCommand(owner, configuration.id),
        operation = LimitRuleChangeOperation.SetAmount(LimitAmount.Bounded(BigDecimal(amount))),
        requestChannelId = channel,
    )

    fun approve(request: UUID) = facade.limitChangeService.approveLimitRuleChange(ApproveLimitRuleChangeCommand(
        tenant, request, LimitChangeActorCommand(LimitChangeActorType.SYSTEM),
    ), TrustedChangeActorContext.InternalSystem)

    fun grant(configuration: LimitConfigurationResult, amount: String = "500") =
        rule(requireNotNull(approve(facade.limitChangeService.requestLimitRuleChange(requestCommand(configuration, amount)).id).limitRuleId))

    fun rule(id: UUID) = facade.limitInquiryService.getLimitRule(GetLimitRuleQuery(tenant, id))

    fun details(request: UUID) = facade.limitInquiryService.getLimitChange(GetLimitChangeQuery(tenant, request))

    fun authorize(source: UUID, amount: String, channelId: UUID = channel) = facade.limitEvaluationService.authorizeTransaction(
        AuthorizeTransactionCommand(tenant, owner, source, operation, channelId, BigDecimal(amount), "EUR"),
    )
}
