package io.github.sepfins.limitguard.api

import io.github.sepfins.limitguard.api.commands.AssignFundingSourceOwnerCommand
import io.github.sepfins.limitguard.api.commands.CreateLimitConfigurationCommand
import io.github.sepfins.limitguard.api.commands.CreateMandateCommand
import io.github.sepfins.limitguard.api.commands.RegisterChannelCommand
import io.github.sepfins.limitguard.api.commands.RegisterFundingSourceCommand
import io.github.sepfins.limitguard.api.commands.RegisterFundingSourceProductCommand
import io.github.sepfins.limitguard.api.commands.RegisterOperationTypeCommand
import io.github.sepfins.limitguard.api.commands.RegisterPartyProfileCommand
import io.github.sepfins.limitguard.api.commands.RegisterTenantCommand
import io.github.sepfins.limitguard.api.results.ChannelResult
import io.github.sepfins.limitguard.api.results.FundingSourceOwnerResult
import io.github.sepfins.limitguard.api.results.FundingSourceProductResult
import io.github.sepfins.limitguard.api.results.FundingSourceResult
import io.github.sepfins.limitguard.api.results.LimitConfigurationResult
import io.github.sepfins.limitguard.api.results.MandateResult
import io.github.sepfins.limitguard.api.results.OperationTypeResult
import io.github.sepfins.limitguard.api.results.PartyProfileResult
import io.github.sepfins.limitguard.api.results.TenantResult

interface TenantCatalogService {
    fun registerTenant(command: RegisterTenantCommand): TenantResult

    fun registerChannel(command: RegisterChannelCommand): ChannelResult

    fun registerOperationType(command: RegisterOperationTypeCommand): OperationTypeResult

    fun registerPartyProfile(command: RegisterPartyProfileCommand): PartyProfileResult

    fun registerFundingSourceProduct(command: RegisterFundingSourceProductCommand): FundingSourceProductResult

    fun registerFundingSource(command: RegisterFundingSourceCommand): FundingSourceResult

    fun assignFundingSourceOwner(command: AssignFundingSourceOwnerCommand): FundingSourceOwnerResult

    fun createMandate(command: CreateMandateCommand): MandateResult

    fun createLimitConfiguration(command: CreateLimitConfigurationCommand): LimitConfigurationResult
}
