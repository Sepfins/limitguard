package io.github.sepfins.limitguard.support

import io.github.sepfins.limitguard.api.LimitAmount
import io.github.sepfins.limitguard.api.FundingSourceCategory
import io.github.sepfins.limitguard.api.LimitChangeActorType
import io.github.sepfins.limitguard.api.LimitKind
import io.github.sepfins.limitguard.api.LimitMandateScope
import io.github.sepfins.limitguard.api.LimitPeriod
import io.github.sepfins.limitguard.api.LimitRuleChangeType
import io.github.sepfins.limitguard.api.LimitUsageConsumptionMode
import io.github.sepfins.limitguard.api.Limitguard
import io.github.sepfins.limitguard.api.NoMatchingRuleBehavior
import io.github.sepfins.limitguard.api.PartyType
import io.github.sepfins.limitguard.api.ProductNodeType
import io.github.sepfins.limitguard.api.TrustedChangeActorContext
import io.github.sepfins.limitguard.api.commands.ApproveLimitRuleChangeCommand
import io.github.sepfins.limitguard.api.commands.AssignFundingSourceOwnerCommand
import io.github.sepfins.limitguard.api.commands.AuthorizeTransactionCommand
import io.github.sepfins.limitguard.api.commands.CreateLimitConfigurationCommand
import io.github.sepfins.limitguard.api.commands.LimitChangeActorCommand
import io.github.sepfins.limitguard.api.commands.LimitRuleDraft
import io.github.sepfins.limitguard.api.commands.RegisterChannelCommand
import io.github.sepfins.limitguard.api.commands.RegisterFundingSourceCommand
import io.github.sepfins.limitguard.api.commands.RegisterFundingSourceProductCommand
import io.github.sepfins.limitguard.api.commands.RegisterOperationTypeCommand
import io.github.sepfins.limitguard.api.commands.RegisterPartyProfileCommand
import io.github.sepfins.limitguard.api.commands.RegisterTenantCommand
import io.github.sepfins.limitguard.api.commands.RequestLimitRuleChangeCommand
import io.github.sepfins.limitguard.api.queries.GetCurrentUsageQuery
import io.github.sepfins.limitguard.api.queries.GetOperationCapacityQuery
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

class OperationSubtreeScenario(
    val facade: Limitguard,
    val now: Instant,
    noMatchingRuleBehavior: NoMatchingRuleBehavior = NoMatchingRuleBehavior.ALLOW,
) {
    val tenant = facade.tenantCatalogService.registerTenant(RegisterTenantCommand(
        "Operation bank ${UUID.randomUUID()}", noMatchingRuleBehavior = noMatchingRuleBehavior,
    )).id
    val owner = profile("Owner")
    val channel = facade.tenantCatalogService.registerChannel(RegisterChannelCommand(tenant, "API", "API")).id
    val product = facade.tenantCatalogService.registerFundingSourceProduct(RegisterFundingSourceProductCommand(
        tenant, "ACCOUNT", "Account", FundingSourceCategory.TRANSACTIONAL, ProductNodeType.PRODUCT, isLeaf = true,
    )).id
    val source = facade.tenantCatalogService.registerFundingSource(RegisterFundingSourceCommand(tenant, product)).id
    private var ruleNumber = 0

    init {
        facade.tenantCatalogService.assignFundingSourceOwner(AssignFundingSourceOwnerCommand(tenant, owner, source))
    }

    fun profile(name: String): UUID = facade.tenantCatalogService.registerPartyProfile(RegisterPartyProfileCommand(
        tenant, PartyType.INDIVIDUAL, firstName = name, lastName = "Customer",
    )).id

    fun operation(code: String, parent: UUID? = null, global: Boolean = false, leaf: Boolean = false): UUID =
        facade.tenantCatalogService.registerOperationType(RegisterOperationTypeCommand(
            tenant, code, code, parentId = parent, isGlobal = global, isLeaf = leaf,
        )).id

    fun grant(
        operation: UUID?,
        amount: String = "500",
        priority: Int = 100,
        isolated: Boolean = false,
        perTransaction: Boolean = false,
        ruleOwner: UUID = owner,
        mandate: UUID? = null,
    ): UUID {
        val number = ++ruleNumber
        val kind = if (perTransaction) LimitKind.PER_TRANSACTION else LimitKind.PERIODIC
        val period = if (perTransaction) null else LimitPeriod.DAILY
        val configuration = facade.tenantCatalogService.createLimitConfiguration(CreateLimitConfigurationCommand(
            tenantId = tenant, code = "LIMIT_$number", name = "Limit $number", limitKind = kind, period = period,
            currency = "EUR", maxAssignableAmount = LimitAmount.Bounded(BigDecimal("10000")), operationTypeId = operation, priority = priority,
        )).id
        val request = facade.limitChangeService.requestLimitRuleChange(RequestLimitRuleChangeCommand(
            tenantId = tenant, changeType = LimitRuleChangeType.CREATE,
            requestedBy = LimitChangeActorCommand(LimitChangeActorType.PARTY_PROFILE, partyProfileId = ruleOwner),
            targetOwnerPartyProfileId = ruleOwner, requestChannelId = channel,
            requestedRule = LimitRuleDraft(
                name = "Rule $number", limitKind = kind, period = period, amount = LimitAmount.Bounded(BigDecimal(amount)), currency = "EUR",
                configurationId = configuration, operationTypeId = operation, priority = priority,
                usageConsumptionMode = if (isolated) LimitUsageConsumptionMode.ISOLATED else LimitUsageConsumptionMode.CASCADING,
                mandateScope = if (mandate == null) LimitMandateScope.ALL_OWNER_MANDATES else LimitMandateScope.MANDATE,
                mandateId = mandate,
            ),
        ))
        return requireNotNull(facade.limitChangeService.approveLimitRuleChange(ApproveLimitRuleChangeCommand(
            tenant, request.id, LimitChangeActorCommand(LimitChangeActorType.SYSTEM),
        ), TrustedChangeActorContext.InternalSystem).limitRuleId)
    }

    fun command(operation: UUID, amount: String = "40", actor: UUID = owner, key: String? = null) =
        AuthorizeTransactionCommand(tenant, actor, source, operation, channel, BigDecimal(amount), "EUR", idempotencyKey = key)

    fun authorize(operation: UUID, amount: String = "40", actor: UUID = owner) =
        facade.limitEvaluationService.authorizeTransaction(command(operation, amount, actor))

    fun capacity(operation: UUID, actor: UUID = owner) = facade.limitInquiryService.getOperationCapacity(
        GetOperationCapacityQuery(tenant, actor, source, operation, channel, "EUR"),
    )

    fun usage(rule: UUID, actor: UUID = owner) = facade.limitInquiryService.getCurrentUsage(
        GetCurrentUsageQuery(tenant, rule, actor),
    )
}
