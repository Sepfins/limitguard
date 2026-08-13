package io.github.sepfins.limitguard.application

import io.github.sepfins.limitguard.api.LimitChangeService
import io.github.sepfins.limitguard.api.LimitEvaluationService
import io.github.sepfins.limitguard.api.Limitguard
import io.github.sepfins.limitguard.api.TenantCatalogService
import io.github.sepfins.limitguard.api.commands.ApplyDueLimitChangesCommand
import io.github.sepfins.limitguard.api.commands.ApproveLimitRuleChangeCommand
import io.github.sepfins.limitguard.api.commands.CancelLimitRuleChangeCommand
import io.github.sepfins.limitguard.api.commands.RejectLimitRuleChangeCommand
import io.github.sepfins.limitguard.api.commands.RequestLimitRuleChangeCommand
import io.github.sepfins.limitguard.api.results.ApplyDueChangesResult
import io.github.sepfins.limitguard.api.results.LimitChangeRequestResult
import io.github.sepfins.limitguard.application.catalog.DefaultTenantCatalogService
import io.github.sepfins.limitguard.application.evaluation.AuthorizationEvaluator
import io.github.sepfins.limitguard.application.evaluation.DefaultLimitEvaluationService
import io.github.sepfins.limitguard.application.evaluation.LimitRuleMatcher
import io.github.sepfins.limitguard.application.evaluation.MandateResolver
import io.github.sepfins.limitguard.application.evaluation.TransactionFactory
import io.github.sepfins.limitguard.application.transaction.TransactionRecorder
import io.github.sepfins.limitguard.application.usage.UsageMutationService
import io.github.sepfins.limitguard.application.usage.UsageReservationService
import io.github.sepfins.limitguard.application.usage.UsageWindowCalculator
import io.github.sepfins.limitguard.port.ids.IdGenerator
import io.github.sepfins.limitguard.port.persistence.ChannelGroupMemberRepository
import io.github.sepfins.limitguard.port.persistence.ChannelRepository
import io.github.sepfins.limitguard.port.persistence.FundingSourceGroupMemberRepository
import io.github.sepfins.limitguard.port.persistence.FundingSourceOwnerRepository
import io.github.sepfins.limitguard.port.persistence.FundingSourceProductRepository
import io.github.sepfins.limitguard.port.persistence.FundingSourceRepository
import io.github.sepfins.limitguard.port.persistence.IndividualRepository
import io.github.sepfins.limitguard.port.persistence.LegalEntityRepository
import io.github.sepfins.limitguard.port.persistence.LimitConfigurationRepository
import io.github.sepfins.limitguard.port.persistence.LimitRuleRepository
import io.github.sepfins.limitguard.port.persistence.MandateRepository
import io.github.sepfins.limitguard.port.persistence.OperationTypeRepository
import io.github.sepfins.limitguard.port.persistence.PartyLegalIdentifierRepository
import io.github.sepfins.limitguard.port.persistence.PartyProfileRepository
import io.github.sepfins.limitguard.port.persistence.PartyRepository
import io.github.sepfins.limitguard.port.persistence.TenantRepository
import io.github.sepfins.limitguard.port.persistence.TransactionEventRepository
import io.github.sepfins.limitguard.port.persistence.TransactionRepository
import io.github.sepfins.limitguard.port.persistence.UsageBucketRepository
import io.github.sepfins.limitguard.port.persistence.UsageEventRepository
import io.github.sepfins.limitguard.port.time.ClockProvider
import io.github.sepfins.limitguard.port.transactions.TransactionRunner

class DefaultLimitguard(
    tenantRepository: TenantRepository,
    channelRepository: ChannelRepository,
    channelGroupMemberRepository: ChannelGroupMemberRepository,
    operationTypeRepository: OperationTypeRepository,
    partyRepository: PartyRepository,
    individualRepository: IndividualRepository,
    legalEntityRepository: LegalEntityRepository,
    partyLegalIdentifierRepository: PartyLegalIdentifierRepository,
    partyProfileRepository: PartyProfileRepository,
    fundingSourceRepository: FundingSourceRepository,
    fundingSourceProductRepository: FundingSourceProductRepository,
    fundingSourceOwnerRepository: FundingSourceOwnerRepository,
    fundingSourceGroupMemberRepository: FundingSourceGroupMemberRepository,
    mandateRepository: MandateRepository,
    limitConfigurationRepository: LimitConfigurationRepository,
    limitRuleRepository: LimitRuleRepository,
    transactionRepository: TransactionRepository,
    transactionEventRepository: TransactionEventRepository,
    usageBucketRepository: UsageBucketRepository,
    usageEventRepository: UsageEventRepository,
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

    override val limitEvaluationService: LimitEvaluationService = DefaultLimitEvaluationService(
        transactionRepository = transactionRepository,
        transactionEventRepository = transactionEventRepository,
        partyProfileRepository = partyProfileRepository,
        tenantRepository = tenantRepository,
        channelRepository = channelRepository,
        operationTypeRepository = operationTypeRepository,
        fundingSourceRepository = fundingSourceRepository,
        fundingSourceOwnerRepository = fundingSourceOwnerRepository,
        transactionFactory = TransactionFactory(idGenerator),
        mandateResolver = MandateResolver(
            mandateRepository = mandateRepository,
            fundingSourceGroupMemberRepository = fundingSourceGroupMemberRepository,
        ),
        limitRuleMatcher = LimitRuleMatcher(
            limitRuleRepository = limitRuleRepository,
            channelGroupMemberRepository = channelGroupMemberRepository,
        ),
        authorizationEvaluator = AuthorizationEvaluator(
            UsageReservationService(
                idGenerator = idGenerator,
                usageBucketRepository = usageBucketRepository,
                usageEventRepository = usageEventRepository,
                usageWindowCalculator = UsageWindowCalculator(),
            ),
        ),
        usageMutationService = UsageMutationService(
            idGenerator = idGenerator,
            transactionEventRepository = transactionEventRepository,
            usageBucketRepository = usageBucketRepository,
            usageEventRepository = usageEventRepository,
        ),
        transactionRecorder = TransactionRecorder(
            transactionRepository = transactionRepository,
            transactionEventRepository = transactionEventRepository,
            idGenerator = idGenerator,
        ),
        clockProvider = clockProvider,
        transactionRunner = transactionRunner,
    )

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
}

private fun notImplemented(): Nothing =
    throw UnsupportedOperationException("Future implementations")
