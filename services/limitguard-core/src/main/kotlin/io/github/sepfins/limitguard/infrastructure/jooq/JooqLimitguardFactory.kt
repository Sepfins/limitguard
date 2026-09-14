package io.github.sepfins.limitguard.infrastructure.jooq

import io.github.sepfins.limitguard.api.Limitguard
import io.github.sepfins.limitguard.application.DefaultLimitguard
import io.github.sepfins.limitguard.infrastructure.ids.SystemIdGenerator
import io.github.sepfins.limitguard.infrastructure.jooq.repository.JooqAuthorizationDecisionRepository
import io.github.sepfins.limitguard.infrastructure.jooq.repository.JooqBankWorkerRepository
import io.github.sepfins.limitguard.infrastructure.jooq.repository.JooqChannelGroupMemberRepository
import io.github.sepfins.limitguard.infrastructure.jooq.repository.JooqChannelGroupRepository
import io.github.sepfins.limitguard.infrastructure.jooq.repository.JooqChannelRepository
import io.github.sepfins.limitguard.infrastructure.jooq.repository.JooqEntityIdentifierRepository
import io.github.sepfins.limitguard.infrastructure.jooq.repository.JooqFundingSourceGroupMemberRepository
import io.github.sepfins.limitguard.infrastructure.jooq.repository.JooqFundingSourceGroupRepository
import io.github.sepfins.limitguard.infrastructure.jooq.repository.JooqFundingSourceOwnerRepository
import io.github.sepfins.limitguard.infrastructure.jooq.repository.JooqFundingSourceProductRepository
import io.github.sepfins.limitguard.infrastructure.jooq.repository.JooqFundingSourceRepository
import io.github.sepfins.limitguard.infrastructure.jooq.repository.JooqIdentifierSchemeRepository
import io.github.sepfins.limitguard.infrastructure.jooq.repository.JooqIndividualRepository
import io.github.sepfins.limitguard.infrastructure.jooq.repository.JooqLegalEntityRepository
import io.github.sepfins.limitguard.infrastructure.jooq.repository.JooqLimitChangePolicyEvaluationRepository
import io.github.sepfins.limitguard.infrastructure.jooq.repository.JooqLimitChangePolicyRepository
import io.github.sepfins.limitguard.infrastructure.jooq.repository.JooqLimitChangeRequestRepository
import io.github.sepfins.limitguard.infrastructure.jooq.repository.JooqLimitConfigurationRepository
import io.github.sepfins.limitguard.infrastructure.jooq.repository.JooqLimitRuleChangeEventRepository
import io.github.sepfins.limitguard.infrastructure.jooq.repository.JooqLimitRuleRepository
import io.github.sepfins.limitguard.infrastructure.jooq.repository.JooqLimitRuleRevisionRepository
import io.github.sepfins.limitguard.infrastructure.jooq.repository.JooqMandateRepository
import io.github.sepfins.limitguard.infrastructure.jooq.repository.JooqOperationTypeRepository
import io.github.sepfins.limitguard.infrastructure.jooq.repository.JooqPartyLegalIdentifierRepository
import io.github.sepfins.limitguard.infrastructure.jooq.repository.JooqPartyProfileDelegationRepository
import io.github.sepfins.limitguard.infrastructure.jooq.repository.JooqPartyProfileRepository
import io.github.sepfins.limitguard.infrastructure.jooq.repository.JooqPartyRepository
import io.github.sepfins.limitguard.infrastructure.jooq.repository.JooqTenantRepository
import io.github.sepfins.limitguard.infrastructure.jooq.repository.JooqTransactionEventRepository
import io.github.sepfins.limitguard.infrastructure.jooq.repository.JooqTransactionRepository
import io.github.sepfins.limitguard.infrastructure.jooq.repository.JooqUsageBucketRepository
import io.github.sepfins.limitguard.infrastructure.jooq.repository.JooqUsageEventRepository
import io.github.sepfins.limitguard.infrastructure.policy.jsonlogic.JsonLogicPolicyEngine
import io.github.sepfins.limitguard.infrastructure.time.SystemClockProvider
import io.github.sepfins.limitguard.port.ids.IdGenerator
import io.github.sepfins.limitguard.port.policy.PolicyEngine
import io.github.sepfins.limitguard.port.time.ClockProvider
import javax.sql.DataSource

class JooqLimitguardFactory(
    private val dataSource: DataSource,
) {
    fun create(
        idGenerator: IdGenerator = SystemIdGenerator(),
        clockProvider: ClockProvider = SystemClockProvider(),
        policyEngines: Collection<PolicyEngine> = listOf(JsonLogicPolicyEngine()),
    ): Limitguard {
        val context = JooqPersistenceContext(dataSource)

        return DefaultLimitguard(
            tenantRepository = JooqTenantRepository(context),
            identifierSchemeRepository = JooqIdentifierSchemeRepository(context),
            entityIdentifierRepository = JooqEntityIdentifierRepository(context),
            channelRepository = JooqChannelRepository(context),
            channelGroupRepository = JooqChannelGroupRepository(context),
            channelGroupMemberRepository = JooqChannelGroupMemberRepository(context),
            operationTypeRepository = JooqOperationTypeRepository(context),
            partyRepository = JooqPartyRepository(context),
            individualRepository = JooqIndividualRepository(context),
            legalEntityRepository = JooqLegalEntityRepository(context),
            partyLegalIdentifierRepository = JooqPartyLegalIdentifierRepository(context),
            partyProfileRepository = JooqPartyProfileRepository(context),
            partyProfileDelegationRepository = JooqPartyProfileDelegationRepository(context),
            bankWorkerRepository = JooqBankWorkerRepository(context),
            fundingSourceRepository = JooqFundingSourceRepository(context),
            fundingSourceProductRepository = JooqFundingSourceProductRepository(context),
            fundingSourceOwnerRepository = JooqFundingSourceOwnerRepository(context),
            fundingSourceGroupRepository = JooqFundingSourceGroupRepository(context),
            fundingSourceGroupMemberRepository = JooqFundingSourceGroupMemberRepository(context),
            mandateRepository = JooqMandateRepository(context),
            limitConfigurationRepository = JooqLimitConfigurationRepository(context),
            limitChangePolicyRepository = JooqLimitChangePolicyRepository(context),
            limitChangePolicyEvaluationRepository = JooqLimitChangePolicyEvaluationRepository(context),
            limitChangeRequestRepository = JooqLimitChangeRequestRepository(context),
            limitChangeEventRepository = JooqLimitRuleChangeEventRepository(context),
            limitRuleRepository = JooqLimitRuleRepository(context),
            limitRuleRevisionRepository = JooqLimitRuleRevisionRepository(context),
            authorizationDecisionRepository = JooqAuthorizationDecisionRepository(context),
            transactionRepository = JooqTransactionRepository(context),
            transactionEventRepository = JooqTransactionEventRepository(context),
            usageBucketRepository = JooqUsageBucketRepository(context),
            usageEventRepository = JooqUsageEventRepository(context),
            policyEngines = policyEngines,
            idGenerator = idGenerator,
            clockProvider = clockProvider,
            transactionRunner = JooqTransactionRunner(context),
        )
    }
}
