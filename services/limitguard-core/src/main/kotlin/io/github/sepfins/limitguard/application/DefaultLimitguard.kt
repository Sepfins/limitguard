package io.github.sepfins.limitguard.application

import io.github.sepfins.limitguard.api.LimitChangeService
import io.github.sepfins.limitguard.api.LimitEvaluationService
import io.github.sepfins.limitguard.api.LimitInquiryService
import io.github.sepfins.limitguard.api.Limitguard
import io.github.sepfins.limitguard.api.TenantCatalogService
import io.github.sepfins.limitguard.api.TenantCatalogQueryService
import io.github.sepfins.limitguard.application.catalog.DefaultTenantCatalogService
import io.github.sepfins.limitguard.application.catalog.DefaultTenantCatalogQueryService
import io.github.sepfins.limitguard.application.change.DefaultLimitChangeService
import io.github.sepfins.limitguard.application.evaluation.AuthorizationEvaluator
import io.github.sepfins.limitguard.application.evaluation.AuthorizationDecisionRecorder
import io.github.sepfins.limitguard.application.evaluation.AuthorizationReferenceResolver
import io.github.sepfins.limitguard.application.evaluation.DefaultLimitEvaluationService
import io.github.sepfins.limitguard.application.evaluation.ExpireDueAuthorizationsWorkflow
import io.github.sepfins.limitguard.application.evaluation.LimitRuleMatcher
import io.github.sepfins.limitguard.application.evaluation.MandateResolver
import io.github.sepfins.limitguard.application.evaluation.TransactionFactory
import io.github.sepfins.limitguard.application.inquiry.DefaultLimitInquiryService
import io.github.sepfins.limitguard.application.transaction.TransactionRecorder
import io.github.sepfins.limitguard.application.usage.UsageMutationService
import io.github.sepfins.limitguard.application.usage.UsageReservationService
import io.github.sepfins.limitguard.application.usage.UsageWindowCalculator
import io.github.sepfins.limitguard.port.ids.IdGenerator
import io.github.sepfins.limitguard.port.persistence.AuthorizationDecisionRepository
import io.github.sepfins.limitguard.port.persistence.ChannelGroupMemberRepository
import io.github.sepfins.limitguard.port.persistence.ChannelGroupRepository
import io.github.sepfins.limitguard.port.persistence.ChannelRepository
import io.github.sepfins.limitguard.port.persistence.EntityIdentifierRepository
import io.github.sepfins.limitguard.port.persistence.FundingSourceGroupMemberRepository
import io.github.sepfins.limitguard.port.persistence.FundingSourceGroupRepository
import io.github.sepfins.limitguard.port.persistence.FundingSourceOwnerRepository
import io.github.sepfins.limitguard.port.persistence.FundingSourceProductRepository
import io.github.sepfins.limitguard.port.persistence.FundingSourceRepository
import io.github.sepfins.limitguard.port.persistence.IndividualRepository
import io.github.sepfins.limitguard.port.persistence.IdentifierSchemeRepository
import io.github.sepfins.limitguard.port.persistence.LegalEntityRepository
import io.github.sepfins.limitguard.port.persistence.LimitChangeRequestRepository
import io.github.sepfins.limitguard.port.persistence.LimitChangePolicyEvaluationRepository
import io.github.sepfins.limitguard.port.persistence.LimitChangePolicyRepository
import io.github.sepfins.limitguard.port.persistence.LimitConfigurationRepository
import io.github.sepfins.limitguard.port.persistence.LimitRuleChangeEventRepository
import io.github.sepfins.limitguard.port.persistence.LimitRuleRepository
import io.github.sepfins.limitguard.port.persistence.LimitRuleRevisionRepository
import io.github.sepfins.limitguard.port.persistence.MandateRepository
import io.github.sepfins.limitguard.port.persistence.OperationTypeRepository
import io.github.sepfins.limitguard.port.persistence.PartyLegalIdentifierRepository
import io.github.sepfins.limitguard.port.persistence.PartyProfileRepository
import io.github.sepfins.limitguard.port.persistence.PartyProfileDelegationRepository
import io.github.sepfins.limitguard.port.persistence.PartyRepository
import io.github.sepfins.limitguard.port.persistence.TenantRepository
import io.github.sepfins.limitguard.port.persistence.TransactionEventRepository
import io.github.sepfins.limitguard.port.persistence.TransactionRepository
import io.github.sepfins.limitguard.port.persistence.UsageBucketRepository
import io.github.sepfins.limitguard.port.persistence.UsageEventRepository
import io.github.sepfins.limitguard.port.policy.PolicyEngine
import io.github.sepfins.limitguard.port.time.ClockProvider
import io.github.sepfins.limitguard.port.transactions.TransactionRunner

class DefaultLimitguard(
    tenantRepository: TenantRepository,
    identifierSchemeRepository: IdentifierSchemeRepository,
    entityIdentifierRepository: EntityIdentifierRepository,
    channelRepository: ChannelRepository,
    channelGroupRepository: ChannelGroupRepository,
    channelGroupMemberRepository: ChannelGroupMemberRepository,
    operationTypeRepository: OperationTypeRepository,
    partyRepository: PartyRepository,
    individualRepository: IndividualRepository,
    legalEntityRepository: LegalEntityRepository,
    partyLegalIdentifierRepository: PartyLegalIdentifierRepository,
    partyProfileRepository: PartyProfileRepository,
    partyProfileDelegationRepository: PartyProfileDelegationRepository,
    fundingSourceRepository: FundingSourceRepository,
    fundingSourceProductRepository: FundingSourceProductRepository,
    fundingSourceOwnerRepository: FundingSourceOwnerRepository,
    fundingSourceGroupRepository: FundingSourceGroupRepository,
    fundingSourceGroupMemberRepository: FundingSourceGroupMemberRepository,
    mandateRepository: MandateRepository,
    limitConfigurationRepository: LimitConfigurationRepository,
    limitChangePolicyRepository: LimitChangePolicyRepository,
    limitChangePolicyEvaluationRepository: LimitChangePolicyEvaluationRepository,
    limitChangeRequestRepository: LimitChangeRequestRepository,
    limitChangeEventRepository: LimitRuleChangeEventRepository,
    limitRuleRepository: LimitRuleRepository,
    limitRuleRevisionRepository: LimitRuleRevisionRepository,
    authorizationDecisionRepository: AuthorizationDecisionRepository,
    transactionRepository: TransactionRepository,
    transactionEventRepository: TransactionEventRepository,
    usageBucketRepository: UsageBucketRepository,
    usageEventRepository: UsageEventRepository,
    policyEngines: Collection<PolicyEngine>,
    idGenerator: IdGenerator,
    clockProvider: ClockProvider,
    transactionRunner: TransactionRunner,
) : Limitguard {
    override val tenantCatalogService: TenantCatalogService = DefaultTenantCatalogService(
        tenantRepository = tenantRepository,
        identifierSchemeRepository = identifierSchemeRepository,
        entityIdentifierRepository = entityIdentifierRepository,
        channelRepository = channelRepository,
        channelGroupRepository = channelGroupRepository,
        channelGroupMemberRepository = channelGroupMemberRepository,
        operationTypeRepository = operationTypeRepository,
        partyRepository = partyRepository,
        individualRepository = individualRepository,
        legalEntityRepository = legalEntityRepository,
        partyLegalIdentifierRepository = partyLegalIdentifierRepository,
        partyProfileRepository = partyProfileRepository,
        partyProfileDelegationRepository = partyProfileDelegationRepository,
        fundingSourceRepository = fundingSourceRepository,
        fundingSourceProductRepository = fundingSourceProductRepository,
        fundingSourceOwnerRepository = fundingSourceOwnerRepository,
        fundingSourceGroupRepository = fundingSourceGroupRepository,
        fundingSourceGroupMemberRepository = fundingSourceGroupMemberRepository,
        mandateRepository = mandateRepository,
        limitConfigurationRepository = limitConfigurationRepository,
        limitChangePolicyRepository = limitChangePolicyRepository,
        policyEngines = policyEngines,
        idGenerator = idGenerator,
        clockProvider = clockProvider,
        transactionRunner = transactionRunner,
    )

    override val tenantCatalogQueryService: TenantCatalogQueryService = DefaultTenantCatalogQueryService(
        tenantRepository = tenantRepository,
        identifierSchemeRepository = identifierSchemeRepository,
        entityIdentifierRepository = entityIdentifierRepository,
        fundingSourceProductRepository = fundingSourceProductRepository,
        transactionRunner = transactionRunner,
    )

    override val limitChangeService: LimitChangeService = DefaultLimitChangeService(
        tenantRepository = tenantRepository,
        partyProfileRepository = partyProfileRepository,
        partyProfileDelegationRepository = partyProfileDelegationRepository,
        channelRepository = channelRepository,
        changeRequestRepository = limitChangeRequestRepository,
        changeEventRepository = limitChangeEventRepository,
        limitRuleRepository = limitRuleRepository,
        limitRuleRevisionRepository = limitRuleRevisionRepository,
        limitConfigurationRepository = limitConfigurationRepository,
        mandateRepository = mandateRepository,
        policyRepository = limitChangePolicyRepository,
        policyEvaluationRepository = limitChangePolicyEvaluationRepository,
        policyEngines = policyEngines,
        idGenerator = idGenerator,
        clockProvider = clockProvider,
        transactionRunner = transactionRunner,
    )

    private val transactionRecorder = TransactionRecorder(
        transactionRepository = transactionRepository,
        transactionEventRepository = transactionEventRepository,
        idGenerator = idGenerator,
    )
    private val usageMutationService = UsageMutationService(
        idGenerator = idGenerator,
        transactionEventRepository = transactionEventRepository,
        usageBucketRepository = usageBucketRepository,
        usageEventRepository = usageEventRepository,
    )

    override val limitEvaluationService: LimitEvaluationService = DefaultLimitEvaluationService(
        authorizationDecisionRecorder = AuthorizationDecisionRecorder(
            repository = authorizationDecisionRepository,
            idGenerator = idGenerator,
        ),
        transactionRepository = transactionRepository,
        transactionEventRepository = transactionEventRepository,
        fundingSourceOwnerRepository = fundingSourceOwnerRepository,
        authorizationReferenceResolver = AuthorizationReferenceResolver(
            tenantRepository = tenantRepository,
            channelRepository = channelRepository,
            operationTypeRepository = operationTypeRepository,
            partyProfileRepository = partyProfileRepository,
            fundingSourceRepository = fundingSourceRepository,
            fundingSourceProductRepository = fundingSourceProductRepository,
        ),
        transactionFactory = TransactionFactory(idGenerator),
        mandateResolver = MandateResolver(
            mandateRepository = mandateRepository,
            fundingSourceGroupMemberRepository = fundingSourceGroupMemberRepository,
        ),
        limitRuleMatcher = LimitRuleMatcher(
            limitRuleRepository = limitRuleRepository,
            channelGroupMemberRepository = channelGroupMemberRepository,
            limitConfigurationRepository = limitConfigurationRepository,
        ),
        authorizationEvaluator = AuthorizationEvaluator(
            UsageReservationService(
                idGenerator = idGenerator,
                usageBucketRepository = usageBucketRepository,
                usageEventRepository = usageEventRepository,
                usageWindowCalculator = UsageWindowCalculator(),
            ),
        ),
        usageMutationService = usageMutationService,
        transactionRecorder = transactionRecorder,
        expireDueAuthorizationsWorkflow = ExpireDueAuthorizationsWorkflow(
            tenantRepository = tenantRepository,
            transactionRepository = transactionRepository,
            usageMutationService = usageMutationService,
            transactionRecorder = transactionRecorder,
            clockProvider = clockProvider,
            transactionRunner = transactionRunner,
        ),
        clockProvider = clockProvider,
        transactionRunner = transactionRunner,
    )

    override val limitInquiryService: LimitInquiryService = DefaultLimitInquiryService(
        tenantRepository = tenantRepository,
        channelRepository = channelRepository,
        channelGroupMemberRepository = channelGroupMemberRepository,
        operationTypeRepository = operationTypeRepository,
        fundingSourceProductRepository = fundingSourceProductRepository,
        fundingSourceRepository = fundingSourceRepository,
        fundingSourceOwnerRepository = fundingSourceOwnerRepository,
        fundingSourceGroupMemberRepository = fundingSourceGroupMemberRepository,
        mandateRepository = mandateRepository,
        limitConfigurationRepository = limitConfigurationRepository,
        partyProfileRepository = partyProfileRepository,
        limitChangeRequestRepository = limitChangeRequestRepository,
        limitChangeEventRepository = limitChangeEventRepository,
        limitRuleRevisionRepository = limitRuleRevisionRepository,
        limitRuleRepository = limitRuleRepository,
        transactionRepository = transactionRepository,
        transactionEventRepository = transactionEventRepository,
        authorizationDecisionRepository = authorizationDecisionRepository,
        usageBucketRepository = usageBucketRepository,
        clockProvider = clockProvider,
        transactionRunner = transactionRunner,
    )
}
