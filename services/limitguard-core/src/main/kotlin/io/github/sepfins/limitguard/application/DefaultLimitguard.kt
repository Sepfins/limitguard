package io.github.sepfins.limitguard.application

import io.github.sepfins.limitguard.api.LimitChangeService
import io.github.sepfins.limitguard.api.LimitEvaluationService
import io.github.sepfins.limitguard.api.Limitguard
import io.github.sepfins.limitguard.api.TenantCatalogService
import io.github.sepfins.limitguard.api.commands.ApplyDueLimitChangesCommand
import io.github.sepfins.limitguard.api.commands.ApproveLimitRuleChangeCommand
import io.github.sepfins.limitguard.api.commands.AuthorizeTransactionCommand
import io.github.sepfins.limitguard.api.commands.CancelLimitRuleChangeCommand
import io.github.sepfins.limitguard.api.commands.CaptureTransactionCommand
import io.github.sepfins.limitguard.api.commands.RejectLimitRuleChangeCommand
import io.github.sepfins.limitguard.api.commands.ReleaseAuthorizationCommand
import io.github.sepfins.limitguard.api.commands.RequestLimitRuleChangeCommand
import io.github.sepfins.limitguard.api.commands.ReverseTransactionCommand
import io.github.sepfins.limitguard.api.results.ApplyDueChangesResult
import io.github.sepfins.limitguard.api.results.AuthorizationResult
import io.github.sepfins.limitguard.api.results.LimitChangeRequestResult
import io.github.sepfins.limitguard.api.results.UsageMutationResult
import io.github.sepfins.limitguard.application.catalog.DefaultTenantCatalogService
import io.github.sepfins.limitguard.port.ids.IdGenerator
import io.github.sepfins.limitguard.port.persistence.ChannelRepository
import io.github.sepfins.limitguard.port.persistence.FundingSourceOwnerRepository
import io.github.sepfins.limitguard.port.persistence.FundingSourceProductRepository
import io.github.sepfins.limitguard.port.persistence.FundingSourceRepository
import io.github.sepfins.limitguard.port.persistence.IndividualRepository
import io.github.sepfins.limitguard.port.persistence.LegalEntityRepository
import io.github.sepfins.limitguard.port.persistence.LimitConfigurationRepository
import io.github.sepfins.limitguard.port.persistence.MandateRepository
import io.github.sepfins.limitguard.port.persistence.OperationTypeRepository
import io.github.sepfins.limitguard.port.persistence.PartyLegalIdentifierRepository
import io.github.sepfins.limitguard.port.persistence.PartyProfileRepository
import io.github.sepfins.limitguard.port.persistence.PartyRepository
import io.github.sepfins.limitguard.port.persistence.TenantRepository
import io.github.sepfins.limitguard.port.time.ClockProvider
import io.github.sepfins.limitguard.port.transactions.TransactionRunner

class DefaultLimitguard(
    tenantRepository: TenantRepository,
    channelRepository: ChannelRepository,
    operationTypeRepository: OperationTypeRepository,
    partyRepository: PartyRepository,
    individualRepository: IndividualRepository,
    legalEntityRepository: LegalEntityRepository,
    partyLegalIdentifierRepository: PartyLegalIdentifierRepository,
    partyProfileRepository: PartyProfileRepository,
    fundingSourceRepository: FundingSourceRepository,
    fundingSourceProductRepository: FundingSourceProductRepository,
    fundingSourceOwnerRepository: FundingSourceOwnerRepository,
    mandateRepository: MandateRepository,
    limitConfigurationRepository: LimitConfigurationRepository,
    idGenerator: IdGenerator,
    clockProvider: ClockProvider,
    transactionRunner: TransactionRunner,
) : Limitguard {
    override val tenantCatalogService: TenantCatalogService = DefaultTenantCatalogService(
        tenantRepository = tenantRepository,
        channelRepository = channelRepository,
        operationTypeRepository = operationTypeRepository,
        partyRepository = partyRepository,
        individualRepository = individualRepository,
        legalEntityRepository = legalEntityRepository,
        partyLegalIdentifierRepository = partyLegalIdentifierRepository,
        partyProfileRepository = partyProfileRepository,
        fundingSourceRepository = fundingSourceRepository,
        fundingSourceProductRepository = fundingSourceProductRepository,
        fundingSourceOwnerRepository = fundingSourceOwnerRepository,
        mandateRepository = mandateRepository,
        limitConfigurationRepository = limitConfigurationRepository,
        idGenerator = idGenerator,
        clockProvider = clockProvider,
        transactionRunner = transactionRunner,
    )

    override val limitChangeService: LimitChangeService = DeferredLimitChangeService

    override val limitEvaluationService: LimitEvaluationService = DeferredLimitEvaluationService

    private object DeferredLimitChangeService : LimitChangeService {
        override fun requestLimitRuleChange(command: RequestLimitRuleChangeCommand): LimitChangeRequestResult =
            notImplemented()

        override fun approveLimitRuleChange(command: ApproveLimitRuleChangeCommand): LimitChangeRequestResult =
            notImplemented()

        override fun rejectLimitRuleChange(command: RejectLimitRuleChangeCommand): LimitChangeRequestResult =
            notImplemented()

        override fun cancelLimitRuleChange(command: CancelLimitRuleChangeCommand): LimitChangeRequestResult =
            notImplemented()

        override fun applyDueLimitChanges(command: ApplyDueLimitChangesCommand): ApplyDueChangesResult =
            notImplemented()
    }

    private object DeferredLimitEvaluationService : LimitEvaluationService {
        override fun authorizeTransaction(command: AuthorizeTransactionCommand): AuthorizationResult =
            notImplemented()

        override fun captureTransaction(command: CaptureTransactionCommand): UsageMutationResult =
            notImplemented()

        override fun releaseAuthorization(command: ReleaseAuthorizationCommand): UsageMutationResult =
            notImplemented()

        override fun reverseTransaction(command: ReverseTransactionCommand): UsageMutationResult =
            notImplemented()
    }
}

private fun notImplemented(): Nothing =
    throw UnsupportedOperationException("Future implementations")
