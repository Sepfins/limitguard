package io.github.sepfins.limitguard.application

import io.github.sepfins.limitguard.api.LimitChangeService
import io.github.sepfins.limitguard.api.LimitEvaluationService
import io.github.sepfins.limitguard.api.Limitguard
import io.github.sepfins.limitguard.api.TenantCatalogService
import io.github.sepfins.limitguard.application.catalog.DefaultTenantCatalogService
import io.github.sepfins.limitguard.application.change.ApproveLimitRuleChangeWorkflow
import io.github.sepfins.limitguard.application.change.ApplyDueLimitChangesWorkflow
import io.github.sepfins.limitguard.application.change.CancelLimitRuleChangeWorkflow
import io.github.sepfins.limitguard.application.change.ChangeCommandValidator
import io.github.sepfins.limitguard.application.change.ChangeReferenceResolver
import io.github.sepfins.limitguard.application.change.DefaultLimitChangeService
import io.github.sepfins.limitguard.application.change.LimitChangeApplicationWorkflow
import io.github.sepfins.limitguard.application.change.LimitChangeEventFactory
import io.github.sepfins.limitguard.application.change.LimitChangePolicyRunner
import io.github.sepfins.limitguard.application.change.LimitChangeRequestFactory
import io.github.sepfins.limitguard.application.change.LimitRuleCandidateFactory
import io.github.sepfins.limitguard.application.change.LimitRuleMandateValidator
import io.github.sepfins.limitguard.application.change.RequestLimitRuleChangeWorkflow
import io.github.sepfins.limitguard.application.change.RejectLimitRuleChangeWorkflow
import io.github.sepfins.limitguard.application.change.PolicyEngineRegistry
import io.github.sepfins.limitguard.application.evaluation.AuthorizationEvaluator
import io.github.sepfins.limitguard.application.evaluation.AuthorizationDecisionRecorder
import io.github.sepfins.limitguard.application.evaluation.AuthorizationReferenceResolver
import io.github.sepfins.limitguard.application.evaluation.DefaultLimitEvaluationService
import io.github.sepfins.limitguard.application.evaluation.ExpireDueAuthorizationsWorkflow
import io.github.sepfins.limitguard.application.evaluation.LimitRuleMatcher
import io.github.sepfins.limitguard.application.evaluation.MandateResolver
import io.github.sepfins.limitguard.application.evaluation.TransactionFactory
import io.github.sepfins.limitguard.application.transaction.TransactionRecorder
import io.github.sepfins.limitguard.application.usage.UsageMutationService
import io.github.sepfins.limitguard.application.usage.UsageReservationService
import io.github.sepfins.limitguard.application.usage.UsageWindowCalculator
import io.github.sepfins.limitguard.infrastructure.policy.jsonlogic.JsonLogicPolicyEngine
import io.github.sepfins.limitguard.port.ids.IdGenerator
import io.github.sepfins.limitguard.port.persistence.BankWorkerRepository
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
    bankWorkerRepository: BankWorkerRepository,
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
    policyEngines: Collection<PolicyEngine> = listOf(JsonLogicPolicyEngine()),
    idGenerator: IdGenerator,
    clockProvider: ClockProvider,
    transactionRunner: TransactionRunner,
) : Limitguard {
    private val limitChangeEventFactory = LimitChangeEventFactory(idGenerator)
    private val limitRuleMandateValidator = LimitRuleMandateValidator(mandateRepository)
    private val limitChangePolicyRunner = LimitChangePolicyRunner(
        policyRepository = limitChangePolicyRepository,
        evaluationRepository = limitChangePolicyEvaluationRepository,
        changeEventRepository = limitChangeEventRepository,
        engineRegistry = PolicyEngineRegistry(policyEngines),
        changeEventFactory = limitChangeEventFactory,
        idGenerator = idGenerator,
    )
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
        bankWorkerRepository = bankWorkerRepository,
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

    override val limitChangeService: LimitChangeService = DefaultLimitChangeService(
        requestWorkflow = RequestLimitRuleChangeWorkflow(
            commandValidator = ChangeCommandValidator(),
            referenceResolver = ChangeReferenceResolver(
                limitConfigurationRepository = limitConfigurationRepository,
                limitRuleRepository = limitRuleRepository,
                limitRuleRevisionRepository = limitRuleRevisionRepository,
            ),
            candidateFactory = LimitRuleCandidateFactory(),
            mandateValidator = limitRuleMandateValidator,
            changeRequestFactory = LimitChangeRequestFactory(),
            changeEventFactory = limitChangeEventFactory,
            policyRunner = limitChangePolicyRunner,
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
            mandateValidator = limitRuleMandateValidator,
            applicationWorkflow = limitChangeApplicationWorkflow,
            changeEventFactory = limitChangeEventFactory,
            changeRequestRepository = limitChangeRequestRepository,
            changeEventRepository = limitChangeEventRepository,
            limitConfigurationRepository = limitConfigurationRepository,
            limitRuleRepository = limitRuleRepository,
            clockProvider = clockProvider,
            transactionRunner = transactionRunner,
        ),
        rejectWorkflow = RejectLimitRuleChangeWorkflow(
            changeEventFactory = limitChangeEventFactory,
            changeRequestRepository = limitChangeRequestRepository,
            changeEventRepository = limitChangeEventRepository,
            clockProvider = clockProvider,
            transactionRunner = transactionRunner,
        ),
        cancelWorkflow = CancelLimitRuleChangeWorkflow(
            changeEventFactory = limitChangeEventFactory,
            changeRequestRepository = limitChangeRequestRepository,
            changeEventRepository = limitChangeEventRepository,
            clockProvider = clockProvider,
            transactionRunner = transactionRunner,
        ),
        applyDueWorkflow = ApplyDueLimitChangesWorkflow(
            applicationWorkflow = limitChangeApplicationWorkflow,
            mandateValidator = limitRuleMandateValidator,
            changeEventFactory = limitChangeEventFactory,
            changeRequestRepository = limitChangeRequestRepository,
            changeEventRepository = limitChangeEventRepository,
            limitConfigurationRepository = limitConfigurationRepository,
            limitRuleRepository = limitRuleRepository,
            clockProvider = clockProvider,
            transactionRunner = transactionRunner,
        ),
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
}
