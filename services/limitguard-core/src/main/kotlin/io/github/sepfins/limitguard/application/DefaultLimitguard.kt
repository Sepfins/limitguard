package io.github.sepfins.limitguard.application

import io.github.sepfins.limitguard.api.LimitChangeService
import io.github.sepfins.limitguard.api.LimitEvaluationService
import io.github.sepfins.limitguard.api.Limitguard
import io.github.sepfins.limitguard.api.TenantCatalogService
import io.github.sepfins.limitguard.application.catalog.DefaultTenantCatalogService
import io.github.sepfins.limitguard.application.change.ApproveLimitRuleChangeWorkflow
import io.github.sepfins.limitguard.application.change.ChangeCommandValidator
import io.github.sepfins.limitguard.application.change.ChangeReferenceResolver
import io.github.sepfins.limitguard.application.change.DefaultLimitChangeService
import io.github.sepfins.limitguard.application.change.LimitChangeApplicationWorkflow
import io.github.sepfins.limitguard.application.change.LimitChangeEventFactory
import io.github.sepfins.limitguard.application.change.LimitChangeRequestFactory
import io.github.sepfins.limitguard.application.change.LimitRuleCandidateFactory
import io.github.sepfins.limitguard.application.change.RequestLimitRuleChangeWorkflow
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
import io.github.sepfins.limitguard.port.persistence.LimitChangeRequestRepository
import io.github.sepfins.limitguard.port.persistence.LimitConfigurationRepository
import io.github.sepfins.limitguard.port.persistence.LimitRuleChangeEventRepository
import io.github.sepfins.limitguard.port.persistence.LimitRuleRepository
import io.github.sepfins.limitguard.port.persistence.LimitRuleRevisionRepository
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
    limitChangeRequestRepository: LimitChangeRequestRepository,
    limitChangeEventRepository: LimitRuleChangeEventRepository,
    limitRuleRepository: LimitRuleRepository,
    limitRuleRevisionRepository: LimitRuleRevisionRepository,
    transactionRepository: TransactionRepository,
    transactionEventRepository: TransactionEventRepository,
    usageBucketRepository: UsageBucketRepository,
    usageEventRepository: UsageEventRepository,
    idGenerator: IdGenerator,
    clockProvider: ClockProvider,
    transactionRunner: TransactionRunner,
) : Limitguard {
    private val limitChangeEventFactory = LimitChangeEventFactory(idGenerator)
    private val limitChangeApplicationWorkflow = LimitChangeApplicationWorkflow(
        changeRequestRepository = limitChangeRequestRepository,
        changeEventRepository = limitChangeEventRepository,
        limitRuleRepository = limitRuleRepository,
        limitRuleRevisionRepository = limitRuleRevisionRepository,
        changeEventFactory = limitChangeEventFactory,
        idGenerator = idGenerator,
    )

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

    override val limitChangeService: LimitChangeService = DefaultLimitChangeService(
        requestWorkflow = RequestLimitRuleChangeWorkflow(
            commandValidator = ChangeCommandValidator(),
            referenceResolver = ChangeReferenceResolver(
                limitConfigurationRepository = limitConfigurationRepository,
                limitRuleRepository = limitRuleRepository,
                limitRuleRevisionRepository = limitRuleRevisionRepository,
            ),
            candidateFactory = LimitRuleCandidateFactory(),
            changeRequestFactory = LimitChangeRequestFactory(),
            changeEventFactory = limitChangeEventFactory,
            applicationWorkflow = limitChangeApplicationWorkflow,
            changeRequestRepository = limitChangeRequestRepository,
            changeEventRepository = limitChangeEventRepository,
            limitRuleRepository = limitRuleRepository,
            idGenerator = idGenerator,
            clockProvider = clockProvider,
            transactionRunner = transactionRunner,
        ),
        approveWorkflow = ApproveLimitRuleChangeWorkflow(
            candidateFactory = LimitRuleCandidateFactory(),
            applicationWorkflow = limitChangeApplicationWorkflow,
            changeEventFactory = limitChangeEventFactory,
            changeRequestRepository = limitChangeRequestRepository,
            changeEventRepository = limitChangeEventRepository,
            limitConfigurationRepository = limitConfigurationRepository,
            limitRuleRepository = limitRuleRepository,
            clockProvider = clockProvider,
            transactionRunner = transactionRunner,
        ),
    )

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
}
