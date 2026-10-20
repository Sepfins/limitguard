package io.github.sepfins.limitguard.api

import io.github.sepfins.limitguard.api.commands.AddChannelGroupMemberCommand
import io.github.sepfins.limitguard.api.commands.AddFundingSourceGroupMemberCommand
import io.github.sepfins.limitguard.api.commands.AssignEntityIdentifierCommand
import io.github.sepfins.limitguard.api.commands.AssignFundingSourceOwnerCommand
import io.github.sepfins.limitguard.api.commands.CreateChannelGroupCommand
import io.github.sepfins.limitguard.api.commands.CreateFundingSourceGroupCommand
import io.github.sepfins.limitguard.api.commands.CreateLimitChangePolicyCommand
import io.github.sepfins.limitguard.api.commands.CreateLimitConfigurationCommand
import io.github.sepfins.limitguard.api.commands.CreateMandateCommand
import io.github.sepfins.limitguard.api.commands.CreatePartyProfileDelegationCommand
import io.github.sepfins.limitguard.api.commands.DeactivateMandateCommand
import io.github.sepfins.limitguard.api.commands.RegisterChannelCommand
import io.github.sepfins.limitguard.api.commands.RegisterFundingSourceCommand
import io.github.sepfins.limitguard.api.commands.RegisterFundingSourceProductCommand
import io.github.sepfins.limitguard.api.commands.RegisterIdentifierSchemeCommand
import io.github.sepfins.limitguard.api.commands.RegisterOperationTypeCommand
import io.github.sepfins.limitguard.api.commands.RegisterPartyProfileCommand
import io.github.sepfins.limitguard.api.commands.RegisterTenantCommand
import io.github.sepfins.limitguard.api.commands.SetChannelActiveCommand
import io.github.sepfins.limitguard.api.commands.SetFundingSourceActiveCommand
import io.github.sepfins.limitguard.api.commands.SetFundingSourceProductActiveCommand
import io.github.sepfins.limitguard.api.commands.SetLimitConfigurationActiveCommand
import io.github.sepfins.limitguard.api.commands.TerminatePartyProfileDelegationCommand
import io.github.sepfins.limitguard.api.commands.UpdateChannelCommand
import io.github.sepfins.limitguard.api.commands.UpdateFundingSourceCommand
import io.github.sepfins.limitguard.api.commands.UpdateFundingSourceProductCommand
import io.github.sepfins.limitguard.api.commands.UpdateLimitConfigurationCommand
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

interface TenantCatalogService {
    fun registerTenant(command: RegisterTenantCommand): TenantResult

    fun registerIdentifierScheme(command: RegisterIdentifierSchemeCommand): IdentifierSchemeResult

    fun assignEntityIdentifier(command: AssignEntityIdentifierCommand): EntityIdentifierResult

    fun registerChannel(command: RegisterChannelCommand): ChannelResult

    fun updateChannel(command: UpdateChannelCommand): ChannelResult

    fun setChannelActive(command: SetChannelActiveCommand): ChannelResult

    fun createChannelGroup(command: CreateChannelGroupCommand): ChannelGroupResult

    fun addChannelGroupMember(command: AddChannelGroupMemberCommand): ChannelGroupMemberResult

    fun registerOperationType(command: RegisterOperationTypeCommand): OperationTypeResult

    fun registerPartyProfile(command: RegisterPartyProfileCommand): PartyProfileResult

    fun createPartyProfileDelegation(command: CreatePartyProfileDelegationCommand): PartyProfileDelegationResult

    fun terminatePartyProfileDelegation(
        command: TerminatePartyProfileDelegationCommand,
    ): PartyProfileDelegationResult

    fun registerFundingSourceProduct(command: RegisterFundingSourceProductCommand): FundingSourceProductResult

    fun updateFundingSourceProduct(command: UpdateFundingSourceProductCommand): FundingSourceProductResult

    fun setFundingSourceProductActive(
        command: SetFundingSourceProductActiveCommand,
    ): FundingSourceProductResult

    fun registerFundingSource(command: RegisterFundingSourceCommand): FundingSourceResult

    fun updateFundingSource(command: UpdateFundingSourceCommand): FundingSourceResult

    fun setFundingSourceActive(command: SetFundingSourceActiveCommand): FundingSourceResult

    fun assignFundingSourceOwner(command: AssignFundingSourceOwnerCommand): FundingSourceOwnerResult

    fun createFundingSourceGroup(command: CreateFundingSourceGroupCommand): FundingSourceGroupResult

    fun addFundingSourceGroupMember(command: AddFundingSourceGroupMemberCommand): FundingSourceGroupMemberResult

    fun createMandate(command: CreateMandateCommand): MandateResult

    fun deactivateMandate(command: DeactivateMandateCommand): MandateResult

    fun createLimitConfiguration(command: CreateLimitConfigurationCommand): LimitConfigurationResult

    fun updateLimitConfiguration(command: UpdateLimitConfigurationCommand): LimitConfigurationResult

    fun setLimitConfigurationActive(
        command: SetLimitConfigurationActiveCommand,
    ): LimitConfigurationResult

    fun createLimitChangePolicy(command: CreateLimitChangePolicyCommand): LimitChangePolicyResult
}
