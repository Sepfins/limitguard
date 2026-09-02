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
import io.github.sepfins.limitguard.api.commands.RegisterBankWorkerCommand
import io.github.sepfins.limitguard.api.commands.RegisterChannelCommand
import io.github.sepfins.limitguard.api.commands.RegisterFundingSourceCommand
import io.github.sepfins.limitguard.api.commands.RegisterFundingSourceProductCommand
import io.github.sepfins.limitguard.api.commands.RegisterIdentifierSchemeCommand
import io.github.sepfins.limitguard.api.commands.RegisterOperationTypeCommand
import io.github.sepfins.limitguard.api.commands.RegisterPartyProfileCommand
import io.github.sepfins.limitguard.api.commands.RegisterTenantCommand
import io.github.sepfins.limitguard.api.results.BankWorkerResult
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

    fun createChannelGroup(command: CreateChannelGroupCommand): ChannelGroupResult

    fun addChannelGroupMember(command: AddChannelGroupMemberCommand): ChannelGroupMemberResult

    fun registerOperationType(command: RegisterOperationTypeCommand): OperationTypeResult

    fun registerPartyProfile(command: RegisterPartyProfileCommand): PartyProfileResult

    fun registerBankWorker(command: RegisterBankWorkerCommand): BankWorkerResult

    fun createPartyProfileDelegation(command: CreatePartyProfileDelegationCommand): PartyProfileDelegationResult

    fun registerFundingSourceProduct(command: RegisterFundingSourceProductCommand): FundingSourceProductResult

    fun registerFundingSource(command: RegisterFundingSourceCommand): FundingSourceResult

    fun assignFundingSourceOwner(command: AssignFundingSourceOwnerCommand): FundingSourceOwnerResult

    fun createFundingSourceGroup(command: CreateFundingSourceGroupCommand): FundingSourceGroupResult

    fun addFundingSourceGroupMember(command: AddFundingSourceGroupMemberCommand): FundingSourceGroupMemberResult

    fun createMandate(command: CreateMandateCommand): MandateResult

    fun createLimitConfiguration(command: CreateLimitConfigurationCommand): LimitConfigurationResult

    fun createLimitChangePolicy(command: CreateLimitChangePolicyCommand): LimitChangePolicyResult
}
