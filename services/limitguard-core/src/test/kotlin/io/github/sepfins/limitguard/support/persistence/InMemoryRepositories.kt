package io.github.sepfins.limitguard.support.persistence

import io.github.sepfins.limitguard.api.errors.ConflictException
import io.github.sepfins.limitguard.api.errors.NotFoundException
import io.github.sepfins.limitguard.domain.change.LimitRuleChangeEvent
import io.github.sepfins.limitguard.domain.change.LimitRuleChangeRequest
import io.github.sepfins.limitguard.domain.change.LimitRuleChangeStatus
import io.github.sepfins.limitguard.domain.change.LimitRuleRevision
import io.github.sepfins.limitguard.domain.change.RequestActorContext
import io.github.sepfins.limitguard.domain.change.ScopeHash
import io.github.sepfins.limitguard.domain.channel.Channel
import io.github.sepfins.limitguard.domain.channel.ChannelCode
import io.github.sepfins.limitguard.domain.common.CurrencyCode
import io.github.sepfins.limitguard.domain.common.ChannelId
import io.github.sepfins.limitguard.domain.common.ExternalReference
import io.github.sepfins.limitguard.domain.common.FundingSourceId
import io.github.sepfins.limitguard.domain.common.FundingSourceOwnerId
import io.github.sepfins.limitguard.domain.common.FundingSourceProductId
import io.github.sepfins.limitguard.domain.common.IdempotencyKey
import io.github.sepfins.limitguard.domain.common.LimitChangeRequestId
import io.github.sepfins.limitguard.domain.common.LimitConfigurationId
import io.github.sepfins.limitguard.domain.common.LimitRuleChangeEventId
import io.github.sepfins.limitguard.domain.common.LimitRuleId
import io.github.sepfins.limitguard.domain.common.LimitRuleRevisionId
import io.github.sepfins.limitguard.domain.common.MandateId
import io.github.sepfins.limitguard.domain.common.OperationTypeId
import io.github.sepfins.limitguard.domain.common.PartyId
import io.github.sepfins.limitguard.domain.common.PartyProfileId
import io.github.sepfins.limitguard.domain.common.PaymentTransactionId
import io.github.sepfins.limitguard.domain.common.TenantId
import io.github.sepfins.limitguard.domain.common.TransactionEventId
import io.github.sepfins.limitguard.domain.common.UsageBucketId
import io.github.sepfins.limitguard.domain.common.UsageEventId
import io.github.sepfins.limitguard.domain.common.UsageWindow
import io.github.sepfins.limitguard.domain.funding.FundingSource
import io.github.sepfins.limitguard.domain.funding.FundingSourceCategory
import io.github.sepfins.limitguard.domain.funding.FundingSourceOwner
import io.github.sepfins.limitguard.domain.funding.FundingSourceProduct
import io.github.sepfins.limitguard.domain.funding.FundingSourceProductCode
import io.github.sepfins.limitguard.domain.funding.FundingSourceScope
import io.github.sepfins.limitguard.domain.limit.LimitChannelScope
import io.github.sepfins.limitguard.domain.limit.LimitConfiguration
import io.github.sepfins.limitguard.domain.limit.LimitConfigurationCode
import io.github.sepfins.limitguard.domain.limit.LimitConfigurationFundingScope
import io.github.sepfins.limitguard.domain.limit.LimitKind
import io.github.sepfins.limitguard.domain.limit.LimitOperationScope
import io.github.sepfins.limitguard.domain.limit.LimitRule
import io.github.sepfins.limitguard.domain.mandate.Mandate
import io.github.sepfins.limitguard.domain.operation.OperationType
import io.github.sepfins.limitguard.domain.operation.OperationTypeCode
import io.github.sepfins.limitguard.domain.operation.OperationTypeScope
import io.github.sepfins.limitguard.domain.party.Party
import io.github.sepfins.limitguard.domain.party.PartyProfile
import io.github.sepfins.limitguard.domain.tenant.Tenant
import io.github.sepfins.limitguard.domain.tenant.TenantName
import io.github.sepfins.limitguard.domain.transaction.PaymentTransaction
import io.github.sepfins.limitguard.domain.transaction.TransactionEvent
import io.github.sepfins.limitguard.domain.usage.LimitUsageBucket
import io.github.sepfins.limitguard.domain.usage.LimitUsageEvent
import io.github.sepfins.limitguard.domain.usage.LimitUsageEventType
import io.github.sepfins.limitguard.port.persistence.ChannelRepository
import io.github.sepfins.limitguard.port.persistence.FundingSourceOwnerRepository
import io.github.sepfins.limitguard.port.persistence.FundingSourceProductRepository
import io.github.sepfins.limitguard.port.persistence.FundingSourceRepository
import io.github.sepfins.limitguard.port.persistence.LimitChangeRequestRepository
import io.github.sepfins.limitguard.port.persistence.LimitConfigurationRepository
import io.github.sepfins.limitguard.port.persistence.LimitRuleChangeEventRepository
import io.github.sepfins.limitguard.port.persistence.LimitRuleRepository
import io.github.sepfins.limitguard.port.persistence.LimitRuleRevisionRepository
import io.github.sepfins.limitguard.port.persistence.MandateRepository
import io.github.sepfins.limitguard.port.persistence.OperationTypeRepository
import io.github.sepfins.limitguard.port.persistence.PartyProfileRepository
import io.github.sepfins.limitguard.port.persistence.PartyRepository
import io.github.sepfins.limitguard.port.persistence.TenantRepository
import io.github.sepfins.limitguard.port.persistence.TransactionEventRepository
import io.github.sepfins.limitguard.port.persistence.TransactionRepository
import io.github.sepfins.limitguard.port.persistence.UsageBucketRepository
import io.github.sepfins.limitguard.port.persistence.UsageEventRepository
import java.util.concurrent.ConcurrentHashMap
import java.time.Instant

class InMemoryLimitguardRepositories(
    val tenants: InMemoryTenantRepository = InMemoryTenantRepository(),
    val parties: InMemoryPartyRepository = InMemoryPartyRepository(),
    val partyProfiles: InMemoryPartyProfileRepository = InMemoryPartyProfileRepository(),
    val channels: InMemoryChannelRepository = InMemoryChannelRepository(),
    val operationTypes: InMemoryOperationTypeRepository = InMemoryOperationTypeRepository(),
    val fundingSourceProducts: InMemoryFundingSourceProductRepository = InMemoryFundingSourceProductRepository(),
    val fundingSources: InMemoryFundingSourceRepository = InMemoryFundingSourceRepository(),
    val fundingSourceOwners: InMemoryFundingSourceOwnerRepository = InMemoryFundingSourceOwnerRepository(),
    val mandates: InMemoryMandateRepository = InMemoryMandateRepository(),
    val limitConfigurations: InMemoryLimitConfigurationRepository = InMemoryLimitConfigurationRepository(),
    val limitRules: InMemoryLimitRuleRepository = InMemoryLimitRuleRepository(),
    val limitChangeRequests: InMemoryLimitChangeRequestRepository = InMemoryLimitChangeRequestRepository(),
    val limitRuleChangeEvents: InMemoryLimitRuleChangeEventRepository = InMemoryLimitRuleChangeEventRepository(),
    val limitRuleRevisions: InMemoryLimitRuleRevisionRepository = InMemoryLimitRuleRevisionRepository(),
    val transactions: InMemoryTransactionRepository = InMemoryTransactionRepository(),
    val transactionEvents: InMemoryTransactionEventRepository = InMemoryTransactionEventRepository(),
    val usageBuckets: InMemoryUsageBucketRepository = InMemoryUsageBucketRepository(),
    val usageEvents: InMemoryUsageEventRepository = InMemoryUsageEventRepository(),
)

class InMemoryTenantRepository : TenantRepository {
    private val lock = Any()
    private val tenants = ConcurrentHashMap<TenantId, Tenant>()

    override fun exists(tenantId: TenantId): Boolean = tenants.containsKey(tenantId)

    override fun findById(tenantId: TenantId): Tenant? = tenants[tenantId]

    override fun findByName(name: TenantName): Tenant? =
        tenants.values.firstOrNull { it.name == name }

    override fun insert(tenant: Tenant): Tenant = synchronized(lock) {
        requireAbsent(tenants.containsKey(tenant.id), "tenant id already exists: ${tenant.id.value}")
        requireAbsent(findByName(tenant.name) != null, "tenant name already exists: ${tenant.name.value}")
        tenants[tenant.id] = tenant
        tenant
    }

    override fun update(tenant: Tenant): Tenant = synchronized(lock) {
        requirePresent(tenants.containsKey(tenant.id), "tenant not found: ${tenant.id.value}")
        requireAbsent(
            tenants.values.any { it.id != tenant.id && it.name == tenant.name },
            "tenant name already exists: ${tenant.name.value}",
        )
        tenants[tenant.id] = tenant
        tenant
    }
}

class InMemoryChannelRepository : ChannelRepository {
    private val lock = Any()
    private val channels = ConcurrentHashMap<TenantScopedKey<ChannelId>, Channel>()

    override fun findActive(tenantId: TenantId): List<Channel> =
        channels.tenantValues(tenantId).filter { it.isActive }

    override fun findById(tenantId: TenantId, channelId: ChannelId): Channel? =
        channels[TenantScopedKey(tenantId, channelId)]

    override fun findByCode(tenantId: TenantId, code: ChannelCode): Channel? =
        channels.tenantValues(tenantId).firstOrNull { it.code == code }

    override fun insert(tenantId: TenantId, channel: Channel): Channel = synchronized(lock) {
        requireSameTenant(tenantId, channel.tenantId)
        requireAbsent(findById(tenantId, channel.id) != null, "channel id already exists: ${channel.id.value}")
        requireAbsent(findByCode(tenantId, channel.code) != null, "channel code already exists: ${channel.code.value}")
        channels[TenantScopedKey(tenantId, channel.id)] = channel
        channel
    }

    override fun update(tenantId: TenantId, channel: Channel): Channel = synchronized(lock) {
        requireSameTenant(tenantId, channel.tenantId)
        requirePresent(findById(tenantId, channel.id) != null, "channel not found: ${channel.id.value}")
        requireAbsent(
            channels.tenantValues(tenantId).any { it.id != channel.id && it.code == channel.code },
            "channel code already exists: ${channel.code.value}",
        )
        channels[TenantScopedKey(tenantId, channel.id)] = channel
        channel
    }
}

class InMemoryOperationTypeRepository : OperationTypeRepository {
    private val lock = Any()
    private val operationTypes = ConcurrentHashMap<OperationTypeId, OperationType>()

    override fun findVisibleToTenant(tenantId: TenantId): List<OperationType> =
        operationTypes.values.filter { it.isVisibleTo(tenantId) }

    override fun findById(tenantId: TenantId, id: OperationTypeId): OperationType? =
        operationTypes[id]?.takeIf { it.isVisibleTo(tenantId) }

    override fun findByCodeVisibleToTenant(tenantId: TenantId, code: OperationTypeCode): List<OperationType> =
        findVisibleToTenant(tenantId).filter { it.code == code }

    override fun findByParentId(tenantId: TenantId, parentId: OperationTypeId): List<OperationType> =
        findVisibleToTenant(tenantId).filter { it.parentId == parentId }

    override fun insert(tenantId: TenantId, operationType: OperationType): OperationType = synchronized(lock) {
        requireScopeMatchesTenant(tenantId, operationType)
        requireAbsent(
            operationTypes.containsKey(operationType.id),
            "operation type id already exists: ${operationType.id.value}",
        )
        requireAbsent(
            operationTypes.values.any { it.sameCodeNamespace(operationType) && it.code == operationType.code },
            "operation type code already exists: ${operationType.code.value}",
        )
        operationTypes[operationType.id] = operationType
        operationType
    }

    override fun update(tenantId: TenantId, operationType: OperationType): OperationType = synchronized(lock) {
        requireScopeMatchesTenant(tenantId, operationType)
        requirePresent(findById(tenantId, operationType.id) != null, "operation type not found: ${operationType.id.value}")
        requireAbsent(
            operationTypes.values.any {
                it.id != operationType.id && it.sameCodeNamespace(operationType) && it.code == operationType.code
            },
            "operation type code already exists: ${operationType.code.value}",
        )
        operationTypes[operationType.id] = operationType
        operationType
    }
}

class InMemoryFundingSourceProductRepository : FundingSourceProductRepository {
    private val lock = Any()
    private val products = ConcurrentHashMap<TenantScopedKey<FundingSourceProductId>, FundingSourceProduct>()

    override fun findActive(tenantId: TenantId): List<FundingSourceProduct> =
        products.tenantValues(tenantId).filter { it.isActive }

    override fun findByParent(tenantId: TenantId, parentId: FundingSourceProductId): List<FundingSourceProduct> =
        products.tenantValues(tenantId).filter { it.parentId == parentId }

    override fun findByAccountCategory(
        tenantId: TenantId,
        accountCategory: FundingSourceCategory,
    ): List<FundingSourceProduct> =
        products.tenantValues(tenantId).filter { it.accountCategory == accountCategory }

    override fun findByCode(tenantId: TenantId, code: FundingSourceProductCode): FundingSourceProduct? =
        products.tenantValues(tenantId).firstOrNull { it.code == code }

    override fun findById(tenantId: TenantId, id: FundingSourceProductId): FundingSourceProduct? =
        products[TenantScopedKey(tenantId, id)]

    override fun insert(tenantId: TenantId, product: FundingSourceProduct): FundingSourceProduct = synchronized(lock) {
        requireSameTenant(tenantId, product.tenantId)
        requireAbsent(findById(tenantId, product.id) != null, "funding source product id already exists: ${product.id.value}")
        requireAbsent(findByCode(tenantId, product.code) != null, "funding source product code already exists: ${product.code.value}")
        requireParentExists(tenantId, product.parentId, ::findById, "funding source product parent not found")
        products[TenantScopedKey(tenantId, product.id)] = product
        product
    }

    override fun update(tenantId: TenantId, product: FundingSourceProduct): FundingSourceProduct = synchronized(lock) {
        requireSameTenant(tenantId, product.tenantId)
        requirePresent(findById(tenantId, product.id) != null, "funding source product not found: ${product.id.value}")
        requireAbsent(
            products.tenantValues(tenantId).any { it.id != product.id && it.code == product.code },
            "funding source product code already exists: ${product.code.value}",
        )
        requireParentExists(tenantId, product.parentId, ::findById, "funding source product parent not found")
        products[TenantScopedKey(tenantId, product.id)] = product
        product
    }
}

class InMemoryFundingSourceRepository : FundingSourceRepository {
    private val lock = Any()
    private val fundingSources = ConcurrentHashMap<TenantScopedKey<FundingSourceId>, FundingSource>()

    override fun findById(tenantId: TenantId, id: FundingSourceId): FundingSource? =
        fundingSources[TenantScopedKey(tenantId, id)]

    override fun findByExternalReference(tenantId: TenantId, externalReference: ExternalReference): FundingSource? =
        fundingSources.tenantValues(tenantId).firstOrNull { it.externalReference == externalReference }

    override fun insert(tenantId: TenantId, fundingSource: FundingSource): FundingSource = synchronized(lock) {
        requireSameTenant(tenantId, fundingSource.tenantId)
        requireAbsent(findById(tenantId, fundingSource.id) != null, "funding source id already exists: ${fundingSource.id.value}")
        requireExternalReferenceUnique(tenantId, fundingSource)
        fundingSources[TenantScopedKey(tenantId, fundingSource.id)] = fundingSource
        fundingSource
    }

    override fun update(tenantId: TenantId, fundingSource: FundingSource): FundingSource = synchronized(lock) {
        requireSameTenant(tenantId, fundingSource.tenantId)
        requirePresent(findById(tenantId, fundingSource.id) != null, "funding source not found: ${fundingSource.id.value}")
        requireExternalReferenceUnique(tenantId, fundingSource)
        fundingSources[TenantScopedKey(tenantId, fundingSource.id)] = fundingSource
        fundingSource
    }

    private fun requireExternalReferenceUnique(tenantId: TenantId, fundingSource: FundingSource) {
        val externalReference = fundingSource.externalReference ?: return
        requireAbsent(
            fundingSources.tenantValues(tenantId).any {
                it.id != fundingSource.id && it.externalReference == externalReference
            },
            "funding source external reference already exists: ${externalReference.value}",
        )
    }
}

class InMemoryFundingSourceOwnerRepository : FundingSourceOwnerRepository {
    private val lock = Any()
    private val owners = ConcurrentHashMap<TenantScopedKey<FundingSourceOwnerId>, FundingSourceOwner>()

    override fun findById(tenantId: TenantId, id: FundingSourceOwnerId): FundingSourceOwner? =
        owners[TenantScopedKey(tenantId, id)]

    override fun findByOwner(tenantId: TenantId, owner: PartyProfileId): List<FundingSourceOwner> =
        owners.tenantValues(tenantId).filter { it.partyProfileId == owner }

    override fun findByFundingSource(tenantId: TenantId, fundingSource: FundingSourceId): List<FundingSourceOwner> =
        owners.tenantValues(tenantId).filter { it.fundingSourceId == fundingSource }

    override fun insert(tenantId: TenantId, fundingSourceOwner: FundingSourceOwner): FundingSourceOwner = synchronized(lock) {
        requireSameTenant(tenantId, fundingSourceOwner.tenantId)
        requireAbsent(
            findById(tenantId, fundingSourceOwner.id) != null,
            "funding source owner id already exists: ${fundingSourceOwner.id.value}",
        )
        requireAbsent(
            owners.tenantValues(tenantId).any {
                it.partyProfileId == fundingSourceOwner.partyProfileId &&
                    it.fundingSourceId == fundingSourceOwner.fundingSourceId
            },
            "funding source owner already exists for party profile and funding source",
        )
        owners[TenantScopedKey(tenantId, fundingSourceOwner.id)] = fundingSourceOwner
        fundingSourceOwner
    }

    override fun update(tenantId: TenantId, fundingSourceOwner: FundingSourceOwner): FundingSourceOwner = synchronized(lock) {
        requireSameTenant(tenantId, fundingSourceOwner.tenantId)
        requirePresent(findById(tenantId, fundingSourceOwner.id) != null, "funding source owner not found: ${fundingSourceOwner.id.value}")
        requireAbsent(
            owners.tenantValues(tenantId).any {
                it.id != fundingSourceOwner.id &&
                    it.partyProfileId == fundingSourceOwner.partyProfileId &&
                    it.fundingSourceId == fundingSourceOwner.fundingSourceId
            },
            "funding source owner already exists for party profile and funding source",
        )
        owners[TenantScopedKey(tenantId, fundingSourceOwner.id)] = fundingSourceOwner
        fundingSourceOwner
    }
}

class InMemoryPartyRepository : PartyRepository {
    private val lock = Any()
    private val parties = ConcurrentHashMap<PartyId, Party>()

    override fun findById(id: PartyId): Party? = parties[id]

    override fun insert(party: Party): Party = synchronized(lock) {
        requireAbsent(parties.containsKey(party.id), "party id already exists: ${party.id.value}")
        parties[party.id] = party
        party
    }

    override fun update(party: Party): Party = synchronized(lock) {
        requirePresent(parties.containsKey(party.id), "party not found: ${party.id.value}")
        parties[party.id] = party
        party
    }
}

class InMemoryPartyProfileRepository : PartyProfileRepository {
    private val lock = Any()
    private val profiles = ConcurrentHashMap<TenantScopedKey<PartyProfileId>, PartyProfile>()

    override fun findById(tenantId: TenantId, id: PartyProfileId): PartyProfile? =
        profiles[TenantScopedKey(tenantId, id)]

    override fun findByPartyId(tenantId: TenantId, partyId: PartyId): List<PartyProfile> =
        profiles.tenantValues(tenantId).filter { it.partyId == partyId }

    override fun insert(tenantId: TenantId, partyProfile: PartyProfile): PartyProfile = synchronized(lock) {
        requireSameTenant(tenantId, partyProfile.tenantId)
        requireAbsent(findById(tenantId, partyProfile.id) != null, "party profile id already exists: ${partyProfile.id.value}")
        requireAbsent(
            profiles.tenantValues(tenantId).any { it.partyId == partyProfile.partyId },
            "party profile already exists for tenant and party",
        )
        profiles[TenantScopedKey(tenantId, partyProfile.id)] = partyProfile
        partyProfile
    }

    override fun update(tenantId: TenantId, partyProfile: PartyProfile): PartyProfile = synchronized(lock) {
        requireSameTenant(tenantId, partyProfile.tenantId)
        requirePresent(findById(tenantId, partyProfile.id) != null, "party profile not found: ${partyProfile.id.value}")
        requireAbsent(
            profiles.tenantValues(tenantId).any { it.id != partyProfile.id && it.partyId == partyProfile.partyId },
            "party profile already exists for tenant and party",
        )
        profiles[TenantScopedKey(tenantId, partyProfile.id)] = partyProfile
        partyProfile
    }
}

class InMemoryMandateRepository : MandateRepository {
    private val lock = Any()
    private val mandates = ConcurrentHashMap<TenantScopedKey<MandateId>, Mandate>()

    override fun findActiveForAssignee(tenantId: TenantId, assignee: PartyProfileId, at: Instant): List<Mandate> =
        mandates.tenantValues(tenantId).filter { it.assigneePartyProfileId == assignee && it.isApplicableAt(at) }

    override fun findActiveForOwner(tenantId: TenantId, owner: PartyProfileId, at: Instant): List<Mandate> =
        mandates.tenantValues(tenantId).filter { it.ownerPartyProfileId == owner && it.isApplicableAt(at) }

    override fun findActiveForOwnerWithScope(
        tenantId: TenantId,
        owner: PartyProfileId,
        at: Instant,
        scope: FundingSourceScope,
    ): List<Mandate> =
        findActiveForOwner(tenantId, owner, at).filter { it.fundingScope == scope }

    override fun findById(tenantId: TenantId, id: MandateId): Mandate? =
        mandates[TenantScopedKey(tenantId, id)]

    override fun insert(tenantId: TenantId, mandate: Mandate): Mandate = synchronized(lock) {
        requireSameTenant(tenantId, mandate.tenantId)
        requireAbsent(findById(tenantId, mandate.id) != null, "mandate id already exists: ${mandate.id.value}")
        requireMandateNameUnique(tenantId, mandate)
        mandates[TenantScopedKey(tenantId, mandate.id)] = mandate
        mandate
    }

    override fun update(tenantId: TenantId, mandate: Mandate): Mandate = synchronized(lock) {
        requireSameTenant(tenantId, mandate.tenantId)
        requirePresent(findById(tenantId, mandate.id) != null, "mandate not found: ${mandate.id.value}")
        requireMandateNameUnique(tenantId, mandate)
        mandates[TenantScopedKey(tenantId, mandate.id)] = mandate
        mandate
    }

    private fun requireMandateNameUnique(tenantId: TenantId, mandate: Mandate) {
        requireAbsent(
            mandates.tenantValues(tenantId).any {
                it.id != mandate.id &&
                    it.ownerPartyProfileId == mandate.ownerPartyProfileId &&
                    it.name == mandate.name
            },
            "mandate name already exists for owner: ${mandate.name.value}",
        )
    }
}

class InMemoryLimitConfigurationRepository : LimitConfigurationRepository {
    private val lock = Any()
    private val configurations = ConcurrentHashMap<TenantScopedKey<LimitConfigurationId>, LimitConfiguration>()

    override fun findAllActive(tenantId: TenantId, at: Instant): List<LimitConfiguration> =
        configurations.tenantValues(tenantId).filter { it.isEffectiveAt(at) }

    override fun findActiveByChannelScope(
        tenantId: TenantId,
        scope: LimitChannelScope,
        at: Instant,
    ): List<LimitConfiguration> =
        findAllActive(tenantId, at).filter { it.channelScope == scope }

    override fun findActiveByLimitKind(tenantId: TenantId, kind: LimitKind, at: Instant): List<LimitConfiguration> =
        findAllActive(tenantId, at).filter { it.limitKind == kind }

    override fun findActiveByFundingScope(
        tenantId: TenantId,
        scope: LimitConfigurationFundingScope,
        at: Instant,
    ): List<LimitConfiguration> =
        findAllActive(tenantId, at).filter { it.fundingScope == scope }

    override fun findActiveByOperationScope(
        tenantId: TenantId,
        scope: LimitOperationScope,
        at: Instant,
    ): List<LimitConfiguration> =
        findAllActive(tenantId, at).filter { it.operationScope == scope }

    override fun findById(tenantId: TenantId, id: LimitConfigurationId): LimitConfiguration? =
        configurations[TenantScopedKey(tenantId, id)]

    override fun findByCode(tenantId: TenantId, code: LimitConfigurationCode): LimitConfiguration? =
        configurations.tenantValues(tenantId).firstOrNull { it.code == code }

    override fun insert(tenantId: TenantId, limitConfiguration: LimitConfiguration): LimitConfiguration = synchronized(lock) {
        requireSameTenant(tenantId, limitConfiguration.tenantId)
        requireAbsent(
            findById(tenantId, limitConfiguration.id) != null,
            "limit configuration id already exists: ${limitConfiguration.id.value}",
        )
        requireAbsent(
            findByCode(tenantId, limitConfiguration.code) != null,
            "limit configuration code already exists: ${limitConfiguration.code.value}",
        )
        requireParentExists(
            tenantId,
            limitConfiguration.parentConfigurationId,
            ::findById,
            "limit configuration parent not found",
        )
        configurations[TenantScopedKey(tenantId, limitConfiguration.id)] = limitConfiguration
        limitConfiguration
    }

    override fun update(tenantId: TenantId, limitConfiguration: LimitConfiguration): LimitConfiguration = synchronized(lock) {
        requireSameTenant(tenantId, limitConfiguration.tenantId)
        requirePresent(findById(tenantId, limitConfiguration.id) != null, "limit configuration not found: ${limitConfiguration.id.value}")
        requireAbsent(
            configurations.tenantValues(tenantId).any {
                it.id != limitConfiguration.id && it.code == limitConfiguration.code
            },
            "limit configuration code already exists: ${limitConfiguration.code.value}",
        )
        requireParentExists(
            tenantId,
            limitConfiguration.parentConfigurationId,
            ::findById,
            "limit configuration parent not found",
        )
        configurations[TenantScopedKey(tenantId, limitConfiguration.id)] = limitConfiguration
        limitConfiguration
    }
}

class InMemoryLimitRuleRepository : LimitRuleRepository {
    private val lock = Any()
    private val limitRules = ConcurrentHashMap<TenantScopedKey<LimitRuleId>, LimitRule>()

    override fun findById(tenantId: TenantId, id: LimitRuleId): LimitRule? =
        limitRules[TenantScopedKey(tenantId, id)]

    override fun findActiveForOwner(tenantId: TenantId, owner: PartyProfileId, at: Instant): List<LimitRule> =
        limitRules.tenantValues(tenantId).filter { it.ownerPartyProfileId == owner && it.isEffectiveAt(at) }

    override fun insert(tenantId: TenantId, limitRule: LimitRule): LimitRule = synchronized(lock) {
        requireSameTenant(tenantId, limitRule.tenantId)
        requireAbsent(findById(tenantId, limitRule.id) != null, "limit rule id already exists: ${limitRule.id.value}")
        requireLimitRuleNameUnique(tenantId, limitRule)
        limitRules[TenantScopedKey(tenantId, limitRule.id)] = limitRule
        limitRule
    }

    override fun update(tenantId: TenantId, limitRule: LimitRule): LimitRule = synchronized(lock) {
        requireSameTenant(tenantId, limitRule.tenantId)
        requirePresent(findById(tenantId, limitRule.id) != null, "limit rule not found: ${limitRule.id.value}")
        requireLimitRuleNameUnique(tenantId, limitRule)
        limitRules[TenantScopedKey(tenantId, limitRule.id)] = limitRule
        limitRule
    }

    private fun requireLimitRuleNameUnique(tenantId: TenantId, limitRule: LimitRule) {
        requireAbsent(
            limitRules.tenantValues(tenantId).any {
                it.id != limitRule.id &&
                    it.ownerPartyProfileId == limitRule.ownerPartyProfileId &&
                    it.name == limitRule.name
            },
            "limit rule name already exists for owner: ${limitRule.name.value}",
        )
    }
}

class InMemoryLimitChangeRequestRepository : LimitChangeRequestRepository {
    private val lock = Any()
    private val requests = ConcurrentHashMap<TenantScopedKey<LimitChangeRequestId>, LimitRuleChangeRequest>()

    override fun findById(tenantId: TenantId, id: LimitChangeRequestId): LimitRuleChangeRequest? =
        requests[TenantScopedKey(tenantId, id)]

    override fun findByIdempotencyKey(tenantId: TenantId, key: IdempotencyKey): LimitRuleChangeRequest? =
        requests.tenantValues(tenantId).firstOrNull { it.idempotencyKey == key }

    override fun findByLimitRule(tenantId: TenantId, limitRuleId: LimitRuleId): List<LimitRuleChangeRequest> =
        requests.tenantValues(tenantId).filter { it.limitRuleId == limitRuleId }

    override fun findPendingForTarget(
        tenantId: TenantId,
        targetScopeHash: ScopeHash,
        at: Instant,
    ): List<LimitRuleChangeRequest> =
        requests.tenantValues(tenantId).filter {
            it.targetScopeHash == targetScopeHash &&
                it.status in PENDING_CHANGE_STATUSES &&
                (it.expiresAt == null || it.expiresAt > at)
        }

    override fun findDueForApplication(tenantId: TenantId, dueAt: Instant, limit: Int): List<LimitRuleChangeRequest> =
        requests.tenantValues(tenantId)
            .filter {
                it.status in DUE_FOR_APPLICATION_STATUSES &&
                    (it.effectiveAt == null || it.effectiveAt <= dueAt) &&
                    (it.expiresAt == null || it.expiresAt > dueAt)
            }
            .sortedBy { it.createdAt }
            .take(limit)

    override fun findByRequesterContext(
        tenantId: TenantId,
        requestActorContext: RequestActorContext,
    ): List<LimitRuleChangeRequest> =
        requests.tenantValues(tenantId).filter { it.requestActorContext == requestActorContext }

    override fun findByRequester(tenantId: TenantId, requester: PartyProfileId): List<LimitRuleChangeRequest> =
        requests.tenantValues(tenantId).filter { it.requesterPartyProfileId == requester }

    override fun insert(tenantId: TenantId, request: LimitRuleChangeRequest): LimitRuleChangeRequest = synchronized(lock) {
        requireSameTenant(tenantId, request.tenantId)
        requireAbsent(findById(tenantId, request.id) != null, "limit change request id already exists: ${request.id.value}")
        requireIdempotencyUnique(tenantId, request)
        requests[TenantScopedKey(tenantId, request.id)] = request
        request
    }

    override fun update(tenantId: TenantId, request: LimitRuleChangeRequest): LimitRuleChangeRequest = synchronized(lock) {
        requireSameTenant(tenantId, request.tenantId)
        requirePresent(findById(tenantId, request.id) != null, "limit change request not found: ${request.id.value}")
        requireIdempotencyUnique(tenantId, request)
        requests[TenantScopedKey(tenantId, request.id)] = request
        request
    }

    private fun requireIdempotencyUnique(tenantId: TenantId, request: LimitRuleChangeRequest) {
        val idempotencyKey = request.idempotencyKey ?: return
        requireAbsent(
            requests.tenantValues(tenantId).any { it.id != request.id && it.idempotencyKey == idempotencyKey },
            "limit change request idempotency key already exists: ${idempotencyKey.value}",
        )
    }
}

class InMemoryLimitRuleChangeEventRepository : LimitRuleChangeEventRepository {
    private val lock = Any()
    private val events = ConcurrentHashMap<TenantScopedKey<LimitRuleChangeEventId>, LimitRuleChangeEvent>()

    override fun findById(tenantId: TenantId, id: LimitRuleChangeEventId): LimitRuleChangeEvent? =
        events[TenantScopedKey(tenantId, id)]

    override fun findByChangeRequest(tenantId: TenantId, changeRequestId: LimitChangeRequestId): List<LimitRuleChangeEvent> =
        events.tenantValues(tenantId).filter { it.changeRequestId == changeRequestId }

    override fun insert(tenantId: TenantId, event: LimitRuleChangeEvent): LimitRuleChangeEvent = synchronized(lock) {
        requireSameTenant(tenantId, event.tenantId)
        requireAbsent(findById(tenantId, event.id) != null, "limit rule change event id already exists: ${event.id.value}")
        events[TenantScopedKey(tenantId, event.id)] = event
        event
    }
}

class InMemoryLimitRuleRevisionRepository : LimitRuleRevisionRepository {
    private val lock = Any()
    private val revisions = ConcurrentHashMap<TenantScopedKey<LimitRuleRevisionId>, LimitRuleRevision>()

    override fun findById(tenantId: TenantId, id: LimitRuleRevisionId): LimitRuleRevision? =
        revisions[TenantScopedKey(tenantId, id)]

    override fun findByLimitRule(tenantId: TenantId, limitRuleId: LimitRuleId): List<LimitRuleRevision> =
        revisions.tenantValues(tenantId).filter { it.limitRuleId == limitRuleId }

    override fun findByChangeRequest(tenantId: TenantId, changeRequestId: LimitChangeRequestId): List<LimitRuleRevision> =
        revisions.tenantValues(tenantId).filter { it.changeRequestId == changeRequestId }

    override fun insert(tenantId: TenantId, revision: LimitRuleRevision): LimitRuleRevision = synchronized(lock) {
        requireSameTenant(tenantId, revision.tenantId)
        requireAbsent(findById(tenantId, revision.id) != null, "limit rule revision id already exists: ${revision.id.value}")
        requireAbsent(
            revisions.tenantValues(tenantId).any {
                it.limitRuleId == revision.limitRuleId && it.revisionNumber == revision.revisionNumber
            },
            "limit rule revision number already exists for rule: ${revision.revisionNumber.value}",
        )
        revisions[TenantScopedKey(tenantId, revision.id)] = revision
        revision
    }
}

class InMemoryTransactionRepository : TransactionRepository {
    private val lock = Any()
    private val transactions = ConcurrentHashMap<TenantScopedKey<PaymentTransactionId>, PaymentTransaction>()

    override fun findByFundingSourceId(tenantId: TenantId, fundingSourceId: FundingSourceId): List<PaymentTransaction> =
        transactions.tenantValues(tenantId).filter { it.fundingSourceId == fundingSourceId }

    override fun findByActingPartyProfileId(tenantId: TenantId, actingPartyProfileId: PartyProfileId): List<PaymentTransaction> =
        transactions.tenantValues(tenantId).filter { it.actingPartyProfileId == actingPartyProfileId }

    override fun findByActingPartyProfileAndFundingSource(
        tenantId: TenantId,
        actingPartyProfileId: PartyProfileId,
        fundingSourceId: FundingSourceId,
    ): List<PaymentTransaction> =
        transactions.tenantValues(tenantId).filter {
            it.actingPartyProfileId == actingPartyProfileId && it.fundingSourceId == fundingSourceId
        }

    override fun findById(tenantId: TenantId, id: PaymentTransactionId): PaymentTransaction? =
        transactions[TenantScopedKey(tenantId, id)]

    override fun findByIdForUpdate(tenantId: TenantId, id: PaymentTransactionId): PaymentTransaction? =
        findById(tenantId, id)

    override fun findByIdempotencyKey(tenantId: TenantId, key: IdempotencyKey): PaymentTransaction? =
        transactions.tenantValues(tenantId).firstOrNull { it.idempotencyKey == key }

    override fun insert(tenantId: TenantId, transaction: PaymentTransaction): PaymentTransaction = synchronized(lock) {
        requireSameTenant(tenantId, transaction.tenantId)
        requireAbsent(findById(tenantId, transaction.id) != null, "transaction id already exists: ${transaction.id.value}")
        requireTransactionIdempotencyUnique(tenantId, transaction)
        transactions[TenantScopedKey(tenantId, transaction.id)] = transaction
        transaction
    }

    override fun update(tenantId: TenantId, transaction: PaymentTransaction): PaymentTransaction = synchronized(lock) {
        requireSameTenant(tenantId, transaction.tenantId)
        requirePresent(findById(tenantId, transaction.id) != null, "transaction not found: ${transaction.id.value}")
        requireTransactionIdempotencyUnique(tenantId, transaction)
        transactions[TenantScopedKey(tenantId, transaction.id)] = transaction
        transaction
    }

    private fun requireTransactionIdempotencyUnique(tenantId: TenantId, transaction: PaymentTransaction) {
        val idempotencyKey = transaction.idempotencyKey ?: return
        requireAbsent(
            transactions.tenantValues(tenantId).any { it.id != transaction.id && it.idempotencyKey == idempotencyKey },
            "transaction idempotency key already exists: ${idempotencyKey.value}",
        )
    }
}

class InMemoryTransactionEventRepository : TransactionEventRepository {
    private val lock = Any()
    private val events = ConcurrentHashMap<TenantScopedKey<TransactionEventId>, TransactionEvent>()

    override fun findById(tenantId: TenantId, id: TransactionEventId): TransactionEvent? =
        events[TenantScopedKey(tenantId, id)]

    override fun findByTransaction(tenantId: TenantId, transactionId: PaymentTransactionId): List<TransactionEvent> =
        events.tenantValues(tenantId).filter { it.paymentTransactionId == transactionId }

    override fun insert(tenantId: TenantId, event: TransactionEvent): TransactionEvent = synchronized(lock) {
        requireSameTenant(tenantId, event.tenantId)
        requireAbsent(findById(tenantId, event.id) != null, "transaction event id already exists: ${event.id.value}")
        events[TenantScopedKey(tenantId, event.id)] = event
        event
    }
}

class InMemoryUsageBucketRepository : UsageBucketRepository {
    private val lock = Any()
    private val buckets = ConcurrentHashMap<TenantScopedKey<UsageBucketId>, LimitUsageBucket>()

    override fun findByLimitRuleInWindow(
        tenantId: TenantId,
        limitRuleId: LimitRuleId,
        window: UsageWindow,
    ): List<LimitUsageBucket> =
        buckets.tenantValues(tenantId).filter { it.limitRuleId == limitRuleId && it.window == window }

    override fun findByLimitRuleAndAssigneeInWindow(
        tenantId: TenantId,
        limitRuleId: LimitRuleId,
        assigneeId: PartyProfileId,
        window: UsageWindow,
    ): List<LimitUsageBucket> =
        findByLimitRuleInWindow(tenantId, limitRuleId, window).filter { it.assigneePartyProfileId == assigneeId }

    override fun findById(tenantId: TenantId, id: UsageBucketId): LimitUsageBucket? =
        buckets[TenantScopedKey(tenantId, id)]

    override fun findForUpdate(
        tenantId: TenantId,
        limitRuleId: LimitRuleId,
        assigneePartyProfileId: PartyProfileId,
        currency: CurrencyCode,
        window: UsageWindow,
    ): LimitUsageBucket? =
        buckets.tenantValues(tenantId).firstOrNull {
            it.limitRuleId == limitRuleId &&
                it.assigneePartyProfileId == assigneePartyProfileId &&
                it.currency == currency &&
                it.window.periodStartsAt == window.periodStartsAt
        }

    override fun insert(tenantId: TenantId, usageBucket: LimitUsageBucket): LimitUsageBucket = synchronized(lock) {
        requireSameTenant(tenantId, usageBucket.tenantId)
        requireAbsent(findById(tenantId, usageBucket.id) != null, "usage bucket id already exists: ${usageBucket.id.value}")
        requireUsageBucketUnique(tenantId, usageBucket)
        buckets[TenantScopedKey(tenantId, usageBucket.id)] = usageBucket
        usageBucket
    }

    override fun update(tenantId: TenantId, usageBucket: LimitUsageBucket): LimitUsageBucket = synchronized(lock) {
        requireSameTenant(tenantId, usageBucket.tenantId)
        requirePresent(findById(tenantId, usageBucket.id) != null, "usage bucket not found: ${usageBucket.id.value}")
        requireUsageBucketUnique(tenantId, usageBucket)
        buckets[TenantScopedKey(tenantId, usageBucket.id)] = usageBucket
        usageBucket
    }

    private fun requireUsageBucketUnique(tenantId: TenantId, usageBucket: LimitUsageBucket) {
        requireAbsent(
            buckets.tenantValues(tenantId).any {
                it.id != usageBucket.id &&
                    it.limitRuleId == usageBucket.limitRuleId &&
                    it.assigneePartyProfileId == usageBucket.assigneePartyProfileId &&
                    it.currency == usageBucket.currency &&
                    it.window.periodStartsAt == usageBucket.window.periodStartsAt
            },
            "usage bucket already exists for rule, assignee, currency, and period start",
        )
    }
}

class InMemoryUsageEventRepository : UsageEventRepository {
    private val lock = Any()
    private val events = ConcurrentHashMap<TenantScopedKey<UsageEventId>, LimitUsageEvent>()

    override fun findById(tenantId: TenantId, id: UsageEventId): LimitUsageEvent? =
        events[TenantScopedKey(tenantId, id)]

    override fun findByBucket(tenantId: TenantId, bucketId: UsageBucketId): List<LimitUsageEvent> =
        events.tenantValues(tenantId).filter { it.usageBucketId == bucketId }

    override fun findByTransactionEvent(tenantId: TenantId, transactionEventId: TransactionEventId): List<LimitUsageEvent> =
        events.tenantValues(tenantId).filter { it.transactionEventId == transactionEventId }

    override fun findByLimitRuleAndTransactionEvent(
        tenantId: TenantId,
        limitRuleId: LimitRuleId,
        transactionEventId: TransactionEventId,
    ): List<LimitUsageEvent> =
        events.tenantValues(tenantId).filter {
            it.limitRuleId == limitRuleId && it.transactionEventId == transactionEventId
        }

    override fun findByLimitRuleTransactionEventAndType(
        tenantId: TenantId,
        limitRuleId: LimitRuleId,
        transactionEventId: TransactionEventId,
        type: LimitUsageEventType,
    ): LimitUsageEvent? =
        findByLimitRuleAndTransactionEvent(tenantId, limitRuleId, transactionEventId)
            .firstOrNull { it.eventType == type }

    override fun insert(tenantId: TenantId, event: LimitUsageEvent): LimitUsageEvent = synchronized(lock) {
        requireSameTenant(tenantId, event.tenantId)
        requireAbsent(findById(tenantId, event.id) != null, "usage event id already exists: ${event.id.value}")
        requireAbsent(
            findByLimitRuleTransactionEventAndType(tenantId, event.limitRuleId, event.transactionEventId, event.eventType) != null,
            "usage event already exists for rule, transaction event, and type",
        )
        events[TenantScopedKey(tenantId, event.id)] = event
        event
    }
}

private data class TenantScopedKey<ID : Any>(
    val tenantId: TenantId,
    val id: ID,
)

private val PENDING_CHANGE_STATUSES = setOf(
    LimitRuleChangeStatus.REQUESTED,
    LimitRuleChangeStatus.APPROVED,
    LimitRuleChangeStatus.PENDING_EFFECTIVE_DATE,
)

private val DUE_FOR_APPLICATION_STATUSES = setOf(
    LimitRuleChangeStatus.APPROVED,
    LimitRuleChangeStatus.PENDING_EFFECTIVE_DATE,
)

private fun <ID : Any, T> ConcurrentHashMap<TenantScopedKey<ID>, T>.tenantValues(tenantId: TenantId): List<T> =
    entries.asSequence()
        .filter { it.key.tenantId == tenantId }
        .map { it.value }
        .toList()

private fun requireAbsent(condition: Boolean, message: String) {
    if (condition) throw ConflictException(message)
}

private fun requirePresent(condition: Boolean, message: String) {
    if (!condition) throw NotFoundException(message)
}

private fun requireSameTenant(expected: TenantId, actual: TenantId) {
    requireAbsent(expected != actual, "tenant mismatch: expected ${expected.value}, got ${actual.value}")
}

private fun <ID : Any, T> requireParentExists(
    tenantId: TenantId,
    parentId: ID?,
    finder: (TenantId, ID) -> T?,
    message: String,
) {
    if (parentId != null) {
        requirePresent(finder(tenantId, parentId) != null, "$message: $parentId")
    }
}

private fun OperationType.isVisibleTo(tenantId: TenantId): Boolean =
    when (val currentScope = scope) {
        OperationTypeScope.Global -> true
        is OperationTypeScope.TenantSpecific -> currentScope.tenantId == tenantId
    }

private fun OperationType.codeNamespaceTenant(): TenantId? =
    when (val currentScope = scope) {
        OperationTypeScope.Global -> null
        is OperationTypeScope.TenantSpecific -> currentScope.tenantId
    }

private fun OperationType.sameCodeNamespace(other: OperationType): Boolean =
    codeNamespaceTenant() == other.codeNamespaceTenant()

private fun requireScopeMatchesTenant(tenantId: TenantId, operationType: OperationType) {
    when (val currentScope = operationType.scope) {
        OperationTypeScope.Global -> Unit
        is OperationTypeScope.TenantSpecific -> requireSameTenant(tenantId, currentScope.tenantId)
    }
}
