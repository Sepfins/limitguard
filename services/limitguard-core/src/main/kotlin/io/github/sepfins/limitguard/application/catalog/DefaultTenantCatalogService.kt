package io.github.sepfins.limitguard.application.catalog

import io.github.sepfins.limitguard.api.LimitChannelScope as ApiLimitChannelScope
import io.github.sepfins.limitguard.api.LimitConfigurationFundingScope as ApiLimitConfigurationFundingScope
import io.github.sepfins.limitguard.api.LimitFundingScope
import io.github.sepfins.limitguard.api.LimitKind as ApiLimitKind
import io.github.sepfins.limitguard.api.TenantCatalogService
import io.github.sepfins.limitguard.api.commands.AssignFundingSourceOwnerCommand
import io.github.sepfins.limitguard.api.commands.CreateLimitConfigurationCommand
import io.github.sepfins.limitguard.api.commands.CreateMandateCommand
import io.github.sepfins.limitguard.api.commands.RegisterChannelCommand
import io.github.sepfins.limitguard.api.commands.RegisterFundingSourceCommand
import io.github.sepfins.limitguard.api.commands.RegisterFundingSourceProductCommand
import io.github.sepfins.limitguard.api.commands.RegisterOperationTypeCommand
import io.github.sepfins.limitguard.api.commands.RegisterPartyProfileCommand
import io.github.sepfins.limitguard.api.commands.RegisterTenantCommand
import io.github.sepfins.limitguard.api.errors.ConflictException
import io.github.sepfins.limitguard.api.errors.NotFoundException
import io.github.sepfins.limitguard.api.errors.ValidationException
import io.github.sepfins.limitguard.api.results.ChannelResult
import io.github.sepfins.limitguard.api.results.FundingSourceOwnerResult
import io.github.sepfins.limitguard.api.results.FundingSourceProductResult
import io.github.sepfins.limitguard.api.results.FundingSourceResult
import io.github.sepfins.limitguard.api.results.LimitConfigurationResult
import io.github.sepfins.limitguard.api.results.MandateResult
import io.github.sepfins.limitguard.api.results.OperationTypeResult
import io.github.sepfins.limitguard.api.results.PartyProfileResult
import io.github.sepfins.limitguard.api.results.TenantResult
import io.github.sepfins.limitguard.domain.channel.Channel
import io.github.sepfins.limitguard.domain.channel.ChannelCode
import io.github.sepfins.limitguard.domain.channel.ChannelName
import io.github.sepfins.limitguard.domain.common.ChannelGroupId
import io.github.sepfins.limitguard.domain.common.ChannelId
import io.github.sepfins.limitguard.domain.common.CurrencyCode
import io.github.sepfins.limitguard.domain.common.EffectiveWindow
import io.github.sepfins.limitguard.domain.common.ExternalReference
import io.github.sepfins.limitguard.domain.common.FundingSourceGroupId
import io.github.sepfins.limitguard.domain.common.FundingSourceId
import io.github.sepfins.limitguard.domain.common.FundingSourceOwnerId
import io.github.sepfins.limitguard.domain.common.FundingSourceProductId
import io.github.sepfins.limitguard.domain.common.LimitConfigurationId
import io.github.sepfins.limitguard.domain.common.MandateId
import io.github.sepfins.limitguard.domain.common.Metadata
import io.github.sepfins.limitguard.domain.common.Money
import io.github.sepfins.limitguard.domain.common.OperationTypeId
import io.github.sepfins.limitguard.domain.common.PartyId
import io.github.sepfins.limitguard.domain.common.PartyLegalIdentifierId
import io.github.sepfins.limitguard.domain.common.PartyProfileDelegationId
import io.github.sepfins.limitguard.domain.common.PartyProfileId
import io.github.sepfins.limitguard.domain.common.TenantId
import io.github.sepfins.limitguard.domain.common.Version
import io.github.sepfins.limitguard.domain.funding.FundingSource
import io.github.sepfins.limitguard.domain.funding.FundingSourceCategory
import io.github.sepfins.limitguard.domain.funding.FundingSourceOwner
import io.github.sepfins.limitguard.domain.funding.FundingSourceOwnerRole
import io.github.sepfins.limitguard.domain.funding.FundingSourceProduct
import io.github.sepfins.limitguard.domain.funding.FundingSourceProductCode
import io.github.sepfins.limitguard.domain.funding.FundingSourceProductName
import io.github.sepfins.limitguard.domain.funding.FundingSourceScope
import io.github.sepfins.limitguard.domain.funding.ProductNodeType
import io.github.sepfins.limitguard.domain.limit.LimitChannelScope as DomainLimitChannelScope
import io.github.sepfins.limitguard.domain.limit.LimitConfiguration
import io.github.sepfins.limitguard.domain.limit.LimitConfigurationCode
import io.github.sepfins.limitguard.domain.limit.LimitConfigurationFundingScope as DomainLimitConfigurationFundingScope
import io.github.sepfins.limitguard.domain.limit.LimitConfigurationName
import io.github.sepfins.limitguard.domain.limit.LimitOperationScope
import io.github.sepfins.limitguard.domain.limit.LimitPeriod
import io.github.sepfins.limitguard.domain.limit.LimitPriority
import io.github.sepfins.limitguard.domain.mandate.Mandate
import io.github.sepfins.limitguard.domain.mandate.MandateName
import io.github.sepfins.limitguard.domain.operation.OperationType
import io.github.sepfins.limitguard.domain.operation.OperationTypeCode
import io.github.sepfins.limitguard.domain.operation.OperationTypeName
import io.github.sepfins.limitguard.domain.operation.OperationTypeScope
import io.github.sepfins.limitguard.domain.party.FirstName
import io.github.sepfins.limitguard.domain.party.Individual
import io.github.sepfins.limitguard.domain.party.JurisdictionCountry
import io.github.sepfins.limitguard.domain.party.LastName
import io.github.sepfins.limitguard.domain.party.LegalEntity
import io.github.sepfins.limitguard.domain.party.LegalForm
import io.github.sepfins.limitguard.domain.party.LegalIdentifierType
import io.github.sepfins.limitguard.domain.party.LegalIdentifierValue
import io.github.sepfins.limitguard.domain.party.LegalName
import io.github.sepfins.limitguard.domain.party.Party
import io.github.sepfins.limitguard.domain.party.PartyLegalIdentifier
import io.github.sepfins.limitguard.domain.party.PartyProfile
import io.github.sepfins.limitguard.domain.tenant.Tenant
import io.github.sepfins.limitguard.domain.tenant.TenantName
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
import java.time.Instant
import io.github.sepfins.limitguard.api.PartyType as ApiPartyType
import io.github.sepfins.limitguard.domain.party.PartyType as DomainPartyType
import io.github.sepfins.limitguard.domain.limit.LimitKind as DomainLimitKind

class DefaultTenantCatalogService(
    private val tenantRepository: TenantRepository,
    private val channelRepository: ChannelRepository,
    private val operationTypeRepository: OperationTypeRepository,
    private val partyRepository: PartyRepository,
    private val individualRepository: IndividualRepository,
    private val legalEntityRepository: LegalEntityRepository,
    private val partyLegalIdentifierRepository: PartyLegalIdentifierRepository,
    private val partyProfileRepository: PartyProfileRepository,
    private val fundingSourceRepository: FundingSourceRepository,
    private val fundingSourceProductRepository: FundingSourceProductRepository,
    private val fundingSourceOwnerRepository: FundingSourceOwnerRepository,
    private val mandateRepository: MandateRepository,
    private val limitConfigurationRepository: LimitConfigurationRepository,
    private val idGenerator: IdGenerator,
    private val clockProvider: ClockProvider,
    private val transactionRunner: TransactionRunner
) : TenantCatalogService {

    override fun registerTenant(command: RegisterTenantCommand): TenantResult = mapValidation {
        val tenant = Tenant(
            id = TenantId.of(idGenerator.newId()),
            name = TenantName.of(command.name),
            config = Metadata(command.config),
            createdAt = clockProvider.now()
        )

        return tenantRepository.insert(tenant).toResult()
    }

    override fun registerChannel(command: RegisterChannelCommand): ChannelResult = mapValidation {
        val tenantId = TenantId.of(command.tenantId)
        requireTenantExists(tenantId)
        val channel = Channel(
            id = ChannelId.of(idGenerator.newId()),
            tenantId = tenantId,
            code = ChannelCode.of(command.code),
            name = ChannelName.of(command.name),
            isActive = command.isActive,
            metadata = Metadata(command.metadata),
            createdAt = clockProvider.now()
        )

        return channelRepository.insert(tenantId, channel).toResult()
    }

    override fun registerOperationType(command: RegisterOperationTypeCommand): OperationTypeResult = mapValidation {
        val tenantId = TenantId.of(command.tenantId)
        requireTenantExists(tenantId)
        val parentId = command.parentId?.let { OperationTypeId.of(it) }
        if (parentId != null) {
            requireOperationTypeVisible(tenantId, parentId)
        }
        val operationType = OperationType(
            id = OperationTypeId.of(idGenerator.newId()),
            scope = if (command.isGlobal) OperationTypeScope.Global else OperationTypeScope.TenantSpecific(tenantId),
            parentId = parentId,
            code = OperationTypeCode.of(command.code),
            name = OperationTypeName.of(command.name),
            isLeaf = command.isLeaf,
            createdAt = clockProvider.now()
        )

        return operationTypeRepository.insert(tenantId, operationType).toResult()
    }

    override fun registerPartyProfile(command: RegisterPartyProfileCommand): PartyProfileResult = mapValidation {
        val tenantId = TenantId.of(command.tenantId)
        requireTenantExists(tenantId)
        val now = clockProvider.now()
        val partyType = command.partyType.toDomain()
        val legalIdentifierInputs = command.legalIdentifiers.map { legalIdentifier ->
            LegalIdentifierInput(
                jurisdictionCountry = JurisdictionCountry.of(legalIdentifier.jurisdictionCountry),
                identifierType = LegalIdentifierType.of(legalIdentifier.identifierType),
                normalizedIdentifier = LegalIdentifierValue.of(legalIdentifier.normalizedIdentifier),
                metadata = Metadata(legalIdentifier.metadata),
            )
        }

        return transactionRunner.inTransaction {
            val resolvedParty = resolveParty(command, partyType, legalIdentifierInputs, now)
            val profile = PartyProfile(
                id = PartyProfileId.of(idGenerator.newId()),
                partyId = resolvedParty.party.id,
                tenantId = tenantId,
                createdAt = now,
            )

            val insertedProfile = partyProfileRepository.insert(tenantId, profile)
            val details = loadPartyDetails(resolvedParty.party)

            insertedProfile.toResult(
                party = resolvedParty.party,
                individual = details.individual,
                legalEntity = details.legalEntity,
                legalIdentifiers = partyLegalIdentifierRepository.findByPartyId(resolvedParty.party.id),
                partyCreated = resolvedParty.partyCreated,
            )
        }
    }

    override fun registerFundingSourceProduct(command: RegisterFundingSourceProductCommand): FundingSourceProductResult = mapValidation {
        val tenantId = TenantId.of(command.tenantId)
        requireTenantExists(tenantId)
        val parentId = command.parentId?.let { FundingSourceProductId.of(it) }
        if (parentId != null) {
            requireFundingSourceProductExists(tenantId, parentId)
        }
        val fundingSourceProduct = FundingSourceProduct(
            id = FundingSourceProductId.of(idGenerator.newId()),
            tenantId = tenantId,
            parentId = parentId,
            accountCategory = FundingSourceCategory.valueOf(command.category.name),
            nodeType = ProductNodeType.valueOf(command.nodeType.name),
            code = FundingSourceProductCode.of(command.code),
            name = FundingSourceProductName.of(command.name),
            isLeaf = command.isLeaf,
            isActive = command.isActive,
            metadata = Metadata(command.metadata),
            createdAt = clockProvider.now()
        )

        return fundingSourceProductRepository.insert(tenantId, fundingSourceProduct).toResult()
    }

    override fun registerFundingSource(command: RegisterFundingSourceCommand): FundingSourceResult = mapValidation {
        val tenantId = TenantId.of(command.tenantId)
        requireTenantExists(tenantId)
        val productId = FundingSourceProductId.of(command.productId)
        val product = requireFundingSourceProductExists(tenantId, productId)
        if (!product.isLeaf) {
            throw ValidationException("funding source product must be a leaf product: ${product.id.value}")
        }
        val fundingSource = FundingSource(
            id = FundingSourceId.of(idGenerator.newId()),
            tenantId = tenantId,
            productId = productId,
            externalReference = command.externalReference?.let { ExternalReference.of(it) },
            isActive = command.isActive,
            metadata = Metadata(command.metadata),
            createdAt = clockProvider.now()
        )

        return fundingSourceRepository.insert(tenantId, fundingSource).toResult()
    }

    override fun assignFundingSourceOwner(command: AssignFundingSourceOwnerCommand): FundingSourceOwnerResult = mapValidation {
        val tenantId = TenantId.of(command.tenantId)
        requireTenantExists(tenantId)
        val partyProfileId = PartyProfileId.of(command.partyProfileId)
        val fundingSourceId = FundingSourceId.of(command.fundingSourceId)
        requirePartyProfileExists(tenantId, partyProfileId)
        requireFundingSourceExists(tenantId, fundingSourceId)
        val fundingSourceOwner = FundingSourceOwner(
            id = FundingSourceOwnerId.of(idGenerator.newId()),
            tenantId = tenantId,
            partyProfileId = partyProfileId,
            fundingSourceId = fundingSourceId,
            ownerRole = FundingSourceOwnerRole.valueOf(command.ownerRole.name),
            createdAt = clockProvider.now()
        )

        return fundingSourceOwnerRepository.insert(tenantId, fundingSourceOwner).toResult()
    }

    override fun createMandate(command: CreateMandateCommand): MandateResult = mapValidation {
        val tenantId = TenantId.of(command.tenantId)
        requireTenantExists(tenantId)
        val ownerPartyProfileId = PartyProfileId.of(command.ownerPartyProfileId)
        val assigneePartyProfileId = PartyProfileId.of(command.assigneePartyProfileId)
        requirePartyProfileExists(tenantId, ownerPartyProfileId)
        requirePartyProfileExists(tenantId, assigneePartyProfileId)
        val now = clockProvider.now()
        val effectiveWindow = EffectiveWindow(
            command.startsAt ?: now,
            command.endsAt
        )
        val mandate = Mandate(
            id = MandateId.of(idGenerator.newId()),
            tenantId = tenantId,
            ownerPartyProfileId = ownerPartyProfileId,
            assigneePartyProfileId = assigneePartyProfileId,
            authorityDelegationId = command.authorityDelegationId?.let { PartyProfileDelegationId.of(it) },
            name = MandateName.of(command.name),
            description = command.description,
            fundingScope = when (command.fundingScope) {
                LimitFundingScope.ALL_OWNER_SOURCES -> {
                    if (command.fundingSourceId != null || command.fundingSourceGroupId != null) {
                        throw ValidationException("Funding source fields must be null for LimitFundingScope.ALL_OWNER_SOURCES")
                    }
                    FundingSourceScope.AllOwnerSources
                }
                LimitFundingScope.FUNDING_SOURCE -> {
                    if (command.fundingSourceGroupId != null) {
                        throw ValidationException("FundingSourceGroupId must be null for LimitFundingScope.FUNDING_SOURCE")
                    }
                    val fundingSourceId = FundingSourceId.of(
                        command.fundingSourceId ?: throw ValidationException("FundingSourceId must not be null for LimitFundingScope.FUNDING_SOURCE"),
                    )
                    requireFundingSourceOwnedBy(tenantId, ownerPartyProfileId, fundingSourceId)
                    FundingSourceScope.FundingSourceSpecific(fundingSourceId)
                }
                LimitFundingScope.FUNDING_SOURCE_GROUP -> {
                    if (command.fundingSourceId != null) {
                        throw ValidationException("FundingSourceId must be null for LimitFundingScope.FUNDING_SOURCE_GROUP")
                    }
                    FundingSourceScope.FundingSourceGroupSpecific(
                        FundingSourceGroupId.of(
                            command.fundingSourceGroupId ?: throw ValidationException("FundingSourceGroupId must not be null for LimitFundingScope.FUNDING_SOURCE_GROUP"),
                        )
                    )
                }
            },
            isActive = effectiveWindow.contains(now),
            version = Version.INITIAL,
            effectiveWindow = effectiveWindow,
            metadata = Metadata(command.metadata),
            createdAt = now
        )

        return mandateRepository.insert(tenantId, mandate).toResult()
    }

    override fun createLimitConfiguration(command: CreateLimitConfigurationCommand): LimitConfigurationResult = mapValidation {
        val tenantId = TenantId.of(command.tenantId)
        requireTenantExists(tenantId)
        val parentConfigurationId = command.parentConfigurationId?.let { LimitConfigurationId.of(it) }
        val parentConfiguration = parentConfigurationId?.let {
            requireLimitConfigurationExists(tenantId, it)
        }
        val now = clockProvider.now()
        val limitConfiguration = LimitConfiguration(
            id = LimitConfigurationId.of(idGenerator.newId()),
            tenantId = tenantId,
            parentConfigurationId = parentConfigurationId,
            code = LimitConfigurationCode.of(command.code),
            name = LimitConfigurationName.of(command.name),
            description = command.description,
            limitKind = when (command.limitKind) {
                ApiLimitKind.PER_TRANSACTION -> {
                    if (command.period != null) {
                        throw ValidationException("Period must be null for LimitKind.PER_TRANSACTION.")
                    }
                    DomainLimitKind.PerTransaction
                }
                ApiLimitKind.PERIODIC -> DomainLimitKind.Periodic(
                    LimitPeriod.valueOf(command.period?.name ?: throw ValidationException("Period must be specified for LimitKind.PERIODIC.")),
                )
            },
            fundingScope = when (command.fundingScope) {
                ApiLimitConfigurationFundingScope.ALL_FUNDING_SOURCES -> {
                    if (command.fundingSourceCategory != null || command.fundingSourceProductId != null) {
                        throw ValidationException("Funding source fields must be null for LimitConfigurationFundingScope.ALL_FUNDING_SOURCES.")
                    }
                    DomainLimitConfigurationFundingScope.AllFundingSources
                }
                ApiLimitConfigurationFundingScope.FUNDING_SOURCE_PRODUCT -> {
                    if (command.fundingSourceCategory != null) {
                        throw ValidationException("Category must be null for LimitConfigurationFundingScope.FUNDING_SOURCE_PRODUCT.")
                    }
                    val productId = FundingSourceProductId.of(
                        command.fundingSourceProductId ?: throw ValidationException("ProductId must not be null for LimitConfigurationFundingScope.FUNDING_SOURCE_PRODUCT"),
                    )
                    requireFundingSourceProductExists(tenantId, productId)
                    DomainLimitConfigurationFundingScope.FundingSourceProductSpecific(productId)
                }
                ApiLimitConfigurationFundingScope.FUNDING_SOURCE_CATEGORY -> {
                    if (command.fundingSourceProductId != null) {
                        throw ValidationException("ProductId must be null for LimitConfigurationFundingScope.FUNDING_SOURCE_CATEGORY.")
                    }
                    DomainLimitConfigurationFundingScope.FundingSourceCategorySpecific(
                        FundingSourceCategory.valueOf(
                            command.fundingSourceCategory?.name ?: throw ValidationException("Category name must be specified for LimitConfigurationFundingScope.FUNDING_SOURCE_CATEGORY.")
                        )
                    )
                }
            },
            channelScope = when (command.channelScope) {
                ApiLimitChannelScope.ALL_CHANNELS -> {
                    if (command.channelId != null || command.channelGroupId != null) {
                        throw ValidationException("Channel fields must be null for LimitChannelScope.ALL_CHANNELS.")
                    }
                    DomainLimitChannelScope.AllChannels
                }
                ApiLimitChannelScope.CHANNEL_GROUP -> {
                    if (command.channelId != null) {
                        throw ValidationException("ChannelId must be null for LimitChannelScope.CHANNEL_GROUP.")
                    }
                    DomainLimitChannelScope.ChannelGroupSpecific(
                        ChannelGroupId.of(
                            command.channelGroupId ?: throw ValidationException("ChannelGroupId must not be null for LimitChannelScope.CHANNEL_GROUP"),
                        )
                    )
                }
                ApiLimitChannelScope.CHANNEL -> {
                    if (command.channelGroupId != null) {
                        throw ValidationException("ChannelGroupId must be null for LimitChannelScope.CHANNEL.")
                    }
                    val channelId = ChannelId.of(
                        command.channelId ?: throw ValidationException("ChannelId must not be null for LimitChannelScope.CHANNEL"),
                    )
                    requireChannelExists(tenantId, channelId)
                    DomainLimitChannelScope.ChannelSpecific(
                        channelId
                    )
                }
            },
            operationScope = command.operationTypeId
                ?.let {
                    val operationTypeId = OperationTypeId.of(it)
                    requireOperationTypeVisible(tenantId, operationTypeId)
                    LimitOperationScope.OperationTypeSpecific(operationTypeId)
                }
                ?: LimitOperationScope.AllOperations,
            maxAssignableAmount = Money.of(command.maxAssignableAmount, CurrencyCode.of(command.currency)),
            defaultAmount = command.defaultAmount?.let { Money.of(it, CurrencyCode.of(command.currency)) },
            priority = LimitPriority.of(command.priority),
            isActive = command.isActive,
            effectiveWindow = EffectiveWindow(command.startsAt ?: now, command.endsAt),
            metadata = Metadata(command.metadata),
            createdAt = now
        )
        if (parentConfiguration != null) {
            limitConfiguration.validateAgainstParent(parentConfiguration)
        }

        return limitConfigurationRepository.insert(tenantId, limitConfiguration).toResult()
    }

    private fun resolveParty(
        command: RegisterPartyProfileCommand,
        partyType: DomainPartyType,
        legalIdentifierInputs: List<LegalIdentifierInput>,
        now: Instant,
    ): ResolvedParty {
        if (command.partyId != null) {
            val partyId = PartyId.of(command.partyId)
            val party = partyRepository.findById(partyId)
                ?: throw NotFoundException("party not found: ${partyId.value}")
            requirePartyType(party, partyType)
            attachLegalIdentifiers(party.id, legalIdentifierInputs, now)
            return ResolvedParty(party = party, partyCreated = false)
        }

        val matchingIdentifiers = legalIdentifierInputs.mapNotNull {
            partyLegalIdentifierRepository.findByValue(
                it.jurisdictionCountry,
                it.identifierType,
                it.normalizedIdentifier,
            )
        }
        val matchingPartyIds = matchingIdentifiers.map { it.partyId }.distinct()

        if (matchingPartyIds.size > 1) {
            throw ConflictException("legal identifiers resolve to different parties")
        }

        if (matchingPartyIds.size == 1) {
            val party = partyRepository.findById(matchingPartyIds.single())
                ?: throw NotFoundException("legal identifier points to a missing party: ${matchingPartyIds.single().value}")
            requirePartyType(party, partyType)
            attachLegalIdentifiers(party.id, legalIdentifierInputs, now)
            return ResolvedParty(party = party, partyCreated = false)
        }

        if (!command.createPartyIfMissing) {
            if (legalIdentifierInputs.isEmpty()) {
                throw ValidationException("partyId or legalIdentifiers is required when createPartyIfMissing is false")
            }
            throw NotFoundException("party not found for supplied legal identifiers")
        }

        val party = partyRepository.insert(
            Party(
                id = PartyId.of(idGenerator.newId()),
                partyType = partyType,
                createdAt = now,
            )
        )
        createPartyDetails(command, party.id, partyType)
        attachLegalIdentifiers(party.id, legalIdentifierInputs, now)

        return ResolvedParty(party = party, partyCreated = true)
    }

    private fun createPartyDetails(
        command: RegisterPartyProfileCommand,
        partyId: PartyId,
        partyType: DomainPartyType,
    ) {
        when (partyType) {
            DomainPartyType.INDIVIDUAL -> individualRepository.insert(
                Individual(
                    partyId = partyId,
                    firstName = FirstName.of(
                        command.firstName ?: throw ValidationException("firstName is required for INDIVIDUAL parties")
                    ),
                    lastName = LastName.of(
                        command.lastName ?: throw ValidationException("lastName is required for INDIVIDUAL parties")
                    ),
                    birthDate = command.birthDate,
                )
            )
            DomainPartyType.LEGAL_ENTITY -> legalEntityRepository.insert(
                LegalEntity(
                    partyId = partyId,
                    legalName = LegalName.of(
                        command.legalName ?: throw ValidationException("legalName is required for LEGAL_ENTITY parties")
                    ),
                    legalForm = command.legalForm?.let { LegalForm.of(it) },
                )
            )
        }
    }

    private fun attachLegalIdentifiers(
        partyId: PartyId,
        legalIdentifierInputs: List<LegalIdentifierInput>,
        now: Instant,
    ): List<PartyLegalIdentifier> =
        legalIdentifierInputs.map { input ->
            val existing = partyLegalIdentifierRepository.findByValue(
                input.jurisdictionCountry,
                input.identifierType,
                input.normalizedIdentifier,
            )

            if (existing != null) {
                if (existing.partyId != partyId) {
                    throw ConflictException(
                        "legal identifier belongs to another party: " +
                            "${input.jurisdictionCountry.value}/${input.identifierType.value}/${input.normalizedIdentifier.value}"
                    )
                }
                existing
            } else {
                partyLegalIdentifierRepository.insert(
                    PartyLegalIdentifier(
                        id = PartyLegalIdentifierId.of(idGenerator.newId()),
                        partyId = partyId,
                        jurisdictionCountry = input.jurisdictionCountry,
                        identifierType = input.identifierType,
                        normalizedIdentifier = input.normalizedIdentifier,
                        metadata = input.metadata,
                        createdAt = now,
                    )
                )
            }
        }

    private fun loadPartyDetails(party: Party): PartyDetails =
        when (party.partyType) {
            DomainPartyType.INDIVIDUAL -> PartyDetails(
                individual = individualRepository.findByPartyId(party.id),
                legalEntity = null,
            )
            DomainPartyType.LEGAL_ENTITY -> PartyDetails(
                individual = null,
                legalEntity = legalEntityRepository.findByPartyId(party.id),
            )
        }

    private fun requirePartyType(party: Party, expected: DomainPartyType) {
        if (party.partyType != expected) {
            throw ConflictException("party type mismatch: expected $expected, found ${party.partyType}")
        }
    }

    private inline fun <T> mapValidation(block: () -> T): T =
        try {
            block()
        } catch (exception: IllegalArgumentException) {
            throw ValidationException(
                message = exception.message ?: "catalog command validation failed",
                cause = exception,
            )
        }

    private fun requireTenantExists(tenantId: TenantId) {
        if (!tenantRepository.exists(tenantId)) {
            throw NotFoundException("tenant not found: ${tenantId.value}")
        }
    }

    private fun requireChannelExists(tenantId: TenantId, channelId: ChannelId): Channel =
        channelRepository.findById(tenantId, channelId)
            ?: throw NotFoundException("channel not found: ${channelId.value}")

    private fun requireOperationTypeVisible(tenantId: TenantId, operationTypeId: OperationTypeId): OperationType =
        operationTypeRepository.findById(tenantId, operationTypeId)
            ?: throw NotFoundException("operation type not found: ${operationTypeId.value}")

    private fun requirePartyProfileExists(tenantId: TenantId, partyProfileId: PartyProfileId): PartyProfile =
        partyProfileRepository.findById(tenantId, partyProfileId)
            ?: throw NotFoundException("party profile not found: ${partyProfileId.value}")

    private fun requireFundingSourceProductExists(
        tenantId: TenantId,
        productId: FundingSourceProductId,
    ): FundingSourceProduct =
        fundingSourceProductRepository.findById(tenantId, productId)
            ?: throw NotFoundException("funding source product not found: ${productId.value}")

    private fun requireFundingSourceExists(tenantId: TenantId, fundingSourceId: FundingSourceId): FundingSource =
        fundingSourceRepository.findById(tenantId, fundingSourceId)
            ?: throw NotFoundException("funding source not found: ${fundingSourceId.value}")

    private fun requireLimitConfigurationExists(
        tenantId: TenantId,
        configurationId: LimitConfigurationId,
    ): LimitConfiguration =
        limitConfigurationRepository.findById(tenantId, configurationId)
            ?: throw NotFoundException("limit configuration not found: ${configurationId.value}")

    private fun requireFundingSourceOwnedBy(
        tenantId: TenantId,
        ownerPartyProfileId: PartyProfileId,
        fundingSourceId: FundingSourceId,
    ) {
        requireFundingSourceExists(tenantId, fundingSourceId)
        val ownerHasSource = fundingSourceOwnerRepository
            .findByFundingSource(tenantId, fundingSourceId)
            .any { it.partyProfileId == ownerPartyProfileId }

        if (!ownerHasSource) {
            throw NotFoundException(
                "funding source ownership not found: " +
                    "owner=${ownerPartyProfileId.value}, fundingSource=${fundingSourceId.value}"
            )
        }
    }

    private fun ApiPartyType.toDomain(): DomainPartyType =
        DomainPartyType.valueOf(name)

    private data class LegalIdentifierInput(
        val jurisdictionCountry: JurisdictionCountry,
        val identifierType: LegalIdentifierType,
        val normalizedIdentifier: LegalIdentifierValue,
        val metadata: Metadata,
    )

    private data class ResolvedParty(
        val party: Party,
        val partyCreated: Boolean,
    )

    private data class PartyDetails(
        val individual: Individual?,
        val legalEntity: LegalEntity?,
    )
}
