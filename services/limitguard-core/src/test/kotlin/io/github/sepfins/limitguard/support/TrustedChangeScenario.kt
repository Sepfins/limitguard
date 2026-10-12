package io.github.sepfins.limitguard.support

import io.github.sepfins.limitguard.api.LimitChangeActorType
import io.github.sepfins.limitguard.api.LimitChangePolicyDecision
import io.github.sepfins.limitguard.api.LimitChangePolicyPhase
import io.github.sepfins.limitguard.api.LimitChannelScope
import io.github.sepfins.limitguard.api.LimitKind
import io.github.sepfins.limitguard.api.Limitguard
import io.github.sepfins.limitguard.api.LimitRuleChangeType
import io.github.sepfins.limitguard.api.PartyProfileDelegationType
import io.github.sepfins.limitguard.api.PartyType
import io.github.sepfins.limitguard.api.commands.CreateLimitChangePolicyCommand
import io.github.sepfins.limitguard.api.commands.CreateLimitConfigurationCommand
import io.github.sepfins.limitguard.api.commands.CreateMandateCommand
import io.github.sepfins.limitguard.api.commands.CreatePartyProfileDelegationCommand
import io.github.sepfins.limitguard.api.commands.LimitChangeActorCommand
import io.github.sepfins.limitguard.api.commands.LimitRuleDraft
import io.github.sepfins.limitguard.api.commands.RegisterChannelCommand
import io.github.sepfins.limitguard.api.commands.RegisterPartyProfileCommand
import io.github.sepfins.limitguard.api.commands.RegisterTenantCommand
import io.github.sepfins.limitguard.api.commands.RequestLimitRuleChangeCommand
import io.github.sepfins.limitguard.api.queries.GetLimitChangeQuery
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

class TrustedChangeScenario(
    val facade: Limitguard,
    val now: Instant,
    createConfiguration: (CreateLimitConfigurationCommand) -> UUID = { facade.tenantCatalogService.createLimitConfiguration(it).id },
) {
    val tenant = facade.tenantCatalogService.registerTenant(RegisterTenantCommand("Actor bank ${UUID.randomUUID()}")).id
    val owner = profile("Owner")
    val representative = profile("Representative")
    val unrelated = profile("Unrelated")
    val channel = facade.tenantCatalogService.registerChannel(RegisterChannelCommand(tenant, "BRANCH", "Branch")).id
    val ruleChannel = facade.tenantCatalogService.registerChannel(RegisterChannelCommand(tenant, "ATM", "ATM")).id
    val configuration = createConfiguration(CreateLimitConfigurationCommand(
        tenantId = tenant, code = "GENERAL", name = "General", limitKind = LimitKind.PER_TRANSACTION,
        currency = "EUR", maxAssignableAmount = BigDecimal("1000"),
        channelScope = LimitChannelScope.CHANNEL, channelId = ruleChannel,
    ))
    val delegation = facade.tenantCatalogService.createPartyProfileDelegation(CreatePartyProfileDelegationCommand(
        tenant, owner, representative, PartyProfileDelegationType.LEGAL_REPRESENTATIVE,
        startsAt = now.minusSeconds(60),
    )).id
    val mandate = facade.tenantCatalogService.createMandate(CreateMandateCommand(
        tenant, owner, representative, "Spending mandate",
    )).id
    val policy = facade.tenantCatalogService.createLimitChangePolicy(CreateLimitChangePolicyCommand(
        tenantId = tenant, code = "REVIEW", name = "Review", phase = LimitChangePolicyPhase.AUTHORIZATION,
        decision = LimitChangePolicyDecision.REQUIRE_APPROVAL,
    )).id
    val command = RequestLimitRuleChangeCommand(
        tenantId = tenant, changeType = LimitRuleChangeType.CREATE, requestedBy = party(owner),
        targetOwnerPartyProfileId = owner, requestChannelId = channel,
        requestedRule = LimitRuleDraft(
            name = "Spending limit", limitKind = LimitKind.PER_TRANSACTION, amount = BigDecimal("500"),
            currency = "EUR", configurationId = configuration,
            channelScope = LimitChannelScope.CHANNEL, channelId = ruleChannel,
        ),
        idempotencyKey = "submission",
    )

    fun profile(name: String): UUID = facade.tenantCatalogService.registerPartyProfile(RegisterPartyProfileCommand(
        tenantId = tenant, partyType = PartyType.INDIVIDUAL, firstName = name, lastName = "Customer",
    )).id

    fun party(id: UUID) = LimitChangeActorCommand(LimitChangeActorType.PARTY_PROFILE, partyProfileId = id)

    fun represented() = command.copy(requestedBy = party(representative), authorityDelegationId = delegation)

    fun details(id: UUID) = facade.limitInquiryService.getLimitChange(GetLimitChangeQuery(tenant, id))
}
