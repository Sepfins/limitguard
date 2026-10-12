package io.github.sepfins.limitguard.support

import io.github.sepfins.limitguard.api.Limitguard
import io.github.sepfins.limitguard.application.DefaultLimitguard
import io.github.sepfins.limitguard.port.persistence.LimitChangePolicyRepository
import io.github.sepfins.limitguard.port.policy.PolicyEngine
import io.github.sepfins.limitguard.port.time.ClockProvider
import io.github.sepfins.limitguard.support.persistence.InMemoryLimitguardRepositories

fun InMemoryLimitguardRepositories.createFacade(
    clock: ClockProvider,
    engines: Collection<PolicyEngine>,
    policies: LimitChangePolicyRepository = limitChangePolicies,
): Limitguard = DefaultLimitguard(
    tenantRepository = tenants,
    identifierSchemeRepository = identifierSchemes,
    entityIdentifierRepository = entityIdentifiers,
    channelRepository = channels,
    channelGroupRepository = channelGroups,
    channelGroupMemberRepository = channelGroupMembers,
    operationTypeRepository = operationTypes,
    partyRepository = parties,
    individualRepository = individuals,
    legalEntityRepository = legalEntities,
    partyLegalIdentifierRepository = partyLegalIdentifiers,
    partyProfileRepository = partyProfiles,
    partyProfileDelegationRepository = partyProfileDelegations,
    fundingSourceRepository = fundingSources,
    fundingSourceProductRepository = fundingSourceProducts,
    fundingSourceOwnerRepository = fundingSourceOwners,
    fundingSourceGroupRepository = fundingSourceGroups,
    fundingSourceGroupMemberRepository = fundingSourceGroupMembers,
    mandateRepository = mandates,
    limitConfigurationRepository = limitConfigurations,
    limitChangePolicyRepository = policies,
    limitChangePolicyEvaluationRepository = limitChangePolicyEvaluations,
    limitChangeRequestRepository = limitChangeRequests,
    limitChangeEventRepository = limitRuleChangeEvents,
    limitRuleRepository = limitRules,
    limitRuleRevisionRepository = limitRuleRevisions,
    authorizationDecisionRepository = authorizationDecisions,
    transactionRepository = transactions,
    transactionEventRepository = transactionEvents,
    usageBucketRepository = usageBuckets,
    usageEventRepository = usageEvents,
    policyEngines = engines,
    idGenerator = SequentialIdGenerator(),
    clockProvider = clock,
    transactionRunner = DirectTransactionRunner(),
)
