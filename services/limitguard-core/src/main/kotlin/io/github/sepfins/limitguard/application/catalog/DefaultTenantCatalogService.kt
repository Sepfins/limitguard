package io.github.sepfins.limitguard.application.catalog

import io.github.sepfins.limitguard.internal.json.JsonSnapshots

import io.github.sepfins.limitguard.api.LimitChannelScope as ApiLimitChannelScope
import io.github.sepfins.limitguard.api.LimitConfigurationFundingScope as ApiLimitConfigurationFundingScope
import io.github.sepfins.limitguard.api.LimitFundingScope
import io.github.sepfins.limitguard.api.LimitKind as ApiLimitKind
import io.github.sepfins.limitguard.api.PartyType as ApiPartyType
import io.github.sepfins.limitguard.api.TenantCatalogService
import io.github.sepfins.limitguard.api.commands.AddChannelGroupMemberCommand
import io.github.sepfins.limitguard.api.commands.AddFundingSourceGroupMemberCommand
import io.github.sepfins.limitguard.api.commands.AssignEntityIdentifierCommand
import io.github.sepfins.limitguard.api.commands.AssignFundingSourceOwnerCommand
import io.github.sepfins.limitguard.api.commands.CreateChannelGroupCommand
import io.github.sepfins.limitguard.api.commands.CreateFundingSourceGroupCommand
import io.github.sepfins.limitguard.api.commands.CreateLimitChangePolicyCommand
import io.github.sepfins.limitguard.api.commands.CreateLimitConfigurationCommand
import io.github.sepfins.limitguard.api.commands.CreateMandateCommand
import io.github.sepfins.limitguard.api.commands.CreatePartyProfileDelegationCommand
import io.github.sepfins.limitguard.api.commands.RegisterBankWorkerCommand
import io.github.sepfins.limitguard.api.commands.RegisterChannelCommand
import io.github.sepfins.limitguard.api.commands.RegisterFundingSourceCommand
import io.github.sepfins.limitguard.api.commands.RegisterFundingSourceProductCommand
import io.github.sepfins.limitguard.api.commands.RegisterIdentifierSchemeCommand
import io.github.sepfins.limitguard.api.commands.RegisterOperationTypeCommand
import io.github.sepfins.limitguard.api.commands.RegisterPartyProfileCommand
import io.github.sepfins.limitguard.api.commands.RegisterTenantCommand
import io.github.sepfins.limitguard.api.commands.SetChannelActiveCommand
import io.github.sepfins.limitguard.api.commands.SetFundingSourceActiveCommand
import io.github.sepfins.limitguard.api.commands.SetFundingSourceProductActiveCommand
import io.github.sepfins.limitguard.api.commands.SetLimitConfigurationActiveCommand
import io.github.sepfins.limitguard.api.commands.UpdateChannelCommand
import io.github.sepfins.limitguard.api.commands.UpdateFundingSourceCommand
import io.github.sepfins.limitguard.api.commands.UpdateFundingSourceProductCommand
import io.github.sepfins.limitguard.api.commands.UpdateLimitConfigurationCommand
import io.github.sepfins.limitguard.api.errors.ConflictException
import io.github.sepfins.limitguard.api.errors.InfrastructureException
import io.github.sepfins.limitguard.api.errors.NotFoundException
import io.github.sepfins.limitguard.api.errors.ValidationException
import io.github.sepfins.limitguard.api.results.BankWorkerResult
import io.github.sepfins.limitguard.api.results.ChannelGroupMemberResult
import io.github.sepfins.limitguard.api.results.ChannelGroupResult
import io.github.sepfins.limitguard.api.results.ChannelResult
import io.github.sepfins.limitguard.api.results.EntityIdentifierResult
import io.github.sepfins.limitguard.api.results.FundingSourceGroupMemberResult
import io.github.sepfins.limitguard.api.results.FundingSourceGroupResult
import io.github.sepfins.limitguard.api.results.FundingSourceOwnerResult
import io.github.sepfins.limitguard.api.results.FundingSourceProductResult
import io.github.sepfins.limitguard.api.results.FundingSourceResult
import io.github.sepfins.limitguard.api.results.IdentifierSchemeResult
import io.github.sepfins.limitguard.api.results.LimitChangePolicyResult
import io.github.sepfins.limitguard.api.results.LimitConfigurationResult
import io.github.sepfins.limitguard.api.results.MandateResult
import io.github.sepfins.limitguard.api.results.OperationTypeResult
import io.github.sepfins.limitguard.api.results.PartyProfileDelegationResult
import io.github.sepfins.limitguard.api.results.PartyProfileResult
import io.github.sepfins.limitguard.api.results.TenantResult
import io.github.sepfins.limitguard.application.configuration.ChannelConfigurationEnvelopes
import io.github.sepfins.limitguard.application.configuration.FundingConfigurationEnvelopes
import io.github.sepfins.limitguard.application.configuration.OperationConfigurationEnvelopes
import io.github.sepfins.limitguard.application.operation.OperationTypeAncestry
import io.github.sepfins.limitguard.domain.channel.Channel
import io.github.sepfins.limitguard.domain.channel.ChannelCode
import io.github.sepfins.limitguard.domain.channel.ChannelGroup
import io.github.sepfins.limitguard.domain.channel.ChannelGroupCode
import io.github.sepfins.limitguard.domain.channel.ChannelGroupMember
import io.github.sepfins.limitguard.domain.channel.ChannelGroupName
import io.github.sepfins.limitguard.domain.channel.ChannelName
import io.github.sepfins.limitguard.domain.change.LimitChangePolicy
import io.github.sepfins.limitguard.domain.change.LimitChangePolicyCapabilities
import io.github.sepfins.limitguard.domain.change.LimitChangePolicyCode
import io.github.sepfins.limitguard.domain.change.LimitChangePolicyDecision
import io.github.sepfins.limitguard.domain.change.LimitChangePolicyEngine
import io.github.sepfins.limitguard.domain.change.LimitChangePolicyName
import io.github.sepfins.limitguard.domain.change.LimitChangePolicyPhase
import io.github.sepfins.limitguard.domain.common.BankWorkerId
import io.github.sepfins.limitguard.domain.common.ChannelGroupId
import io.github.sepfins.limitguard.domain.common.ChannelId
import io.github.sepfins.limitguard.domain.common.CurrencyCode
import io.github.sepfins.limitguard.domain.common.EffectiveWindow
import io.github.sepfins.limitguard.domain.common.EntityIdentifierId
import io.github.sepfins.limitguard.domain.common.ExternalReference
import io.github.sepfins.limitguard.domain.common.FundingSourceGroupId
import io.github.sepfins.limitguard.domain.common.FundingSourceId
import io.github.sepfins.limitguard.domain.common.FundingSourceOwnerId
import io.github.sepfins.limitguard.domain.common.FundingSourceProductId
import io.github.sepfins.limitguard.domain.common.IdentifierSchemeId
import io.github.sepfins.limitguard.domain.common.LimitChangePolicyId
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
import io.github.sepfins.limitguard.domain.funding.FundingSourceGroup
import io.github.sepfins.limitguard.domain.funding.FundingSourceGroupCode
import io.github.sepfins.limitguard.domain.funding.FundingSourceGroupMember
import io.github.sepfins.limitguard.domain.funding.FundingSourceGroupName
import io.github.sepfins.limitguard.domain.funding.FundingSourceProduct
import io.github.sepfins.limitguard.domain.funding.FundingSourceProductCode
import io.github.sepfins.limitguard.domain.funding.FundingSourceProductName
import io.github.sepfins.limitguard.domain.funding.FundingSourceScope
import io.github.sepfins.limitguard.domain.funding.ProductNodeType
import io.github.sepfins.limitguard.domain.identifier.EntityIdentifier
import io.github.sepfins.limitguard.domain.identifier.EntityType
import io.github.sepfins.limitguard.domain.identifier.IdentifierScheme
import io.github.sepfins.limitguard.domain.identifier.IdentifierValue
import io.github.sepfins.limitguard.domain.identifier.SchemeName
import io.github.sepfins.limitguard.domain.limit.LimitChannelScope as DomainLimitChannelScope
import io.github.sepfins.limitguard.domain.limit.LimitConfiguration
import io.github.sepfins.limitguard.domain.limit.LimitConfigurationCode
import io.github.sepfins.limitguard.domain.limit.LimitConfigurationFundingScope as DomainLimitConfigurationFundingScope
import io.github.sepfins.limitguard.domain.limit.LimitKind as DomainLimitKind
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
import io.github.sepfins.limitguard.domain.party.PartyProfileDelegation
import io.github.sepfins.limitguard.domain.party.PartyProfileDelegationType
import io.github.sepfins.limitguard.domain.party.PartyType as DomainPartyType
import io.github.sepfins.limitguard.domain.tenant.Tenant
import io.github.sepfins.limitguard.domain.tenant.TenantName
import io.github.sepfins.limitguard.domain.tenant.NoMatchingRuleBehavior
import io.github.sepfins.limitguard.domain.worker.BankWorker
import io.github.sepfins.limitguard.domain.worker.BranchCode
import io.github.sepfins.limitguard.domain.worker.DisplayName
import io.github.sepfins.limitguard.port.ids.IdGenerator
import io.github.sepfins.limitguard.port.persistence.BankWorkerRepository
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
import io.github.sepfins.limitguard.port.persistence.LimitConfigurationRepository
import io.github.sepfins.limitguard.port.persistence.LimitChangePolicyRepository
import io.github.sepfins.limitguard.port.persistence.MandateRepository
import io.github.sepfins.limitguard.port.persistence.OperationTypeRepository
import io.github.sepfins.limitguard.port.persistence.PartyLegalIdentifierRepository
import io.github.sepfins.limitguard.port.persistence.PartyProfileRepository
import io.github.sepfins.limitguard.port.persistence.PartyProfileDelegationRepository
import io.github.sepfins.limitguard.port.persistence.PartyRepository
import io.github.sepfins.limitguard.port.persistence.TenantRepository
import io.github.sepfins.limitguard.port.policy.PolicyEngine
import io.github.sepfins.limitguard.port.time.ClockProvider
import io.github.sepfins.limitguard.port.transactions.TransactionRunner
import java.time.Instant

class DefaultTenantCatalogService(
    private val tenantRepository: TenantRepository,
    private val identifierSchemeRepository: IdentifierSchemeRepository,
    private val entityIdentifierRepository: EntityIdentifierRepository,
    private val channelRepository: ChannelRepository,
    private val channelGroupRepository: ChannelGroupRepository,
    private val channelGroupMemberRepository: ChannelGroupMemberRepository,
    private val operationTypeRepository: OperationTypeRepository,
    private val partyRepository: PartyRepository,
    private val individualRepository: IndividualRepository,
    private val legalEntityRepository: LegalEntityRepository,
    private val partyLegalIdentifierRepository: PartyLegalIdentifierRepository,
    private val partyProfileRepository: PartyProfileRepository,
    private val partyProfileDelegationRepository: PartyProfileDelegationRepository,
    private val bankWorkerRepository: BankWorkerRepository,
    private val fundingSourceRepository: FundingSourceRepository,
    private val fundingSourceProductRepository: FundingSourceProductRepository,
    private val fundingSourceOwnerRepository: FundingSourceOwnerRepository,
    private val fundingSourceGroupRepository: FundingSourceGroupRepository,
    private val fundingSourceGroupMemberRepository: FundingSourceGroupMemberRepository,
    private val mandateRepository: MandateRepository,
    private val limitConfigurationRepository: LimitConfigurationRepository,
    private val limitChangePolicyRepository: LimitChangePolicyRepository,
    private val policyEngines: Collection<PolicyEngine>,
    private val idGenerator: IdGenerator,
    private val clockProvider: ClockProvider,
    private val transactionRunner: TransactionRunner
) : TenantCatalogService {
    private val fundingEnvelopes = FundingConfigurationEnvelopes(
        limitConfigurationRepository, fundingSourceProductRepository,
    )
    private val channelEnvelopes = ChannelConfigurationEnvelopes(
        limitConfigurationRepository, channelRepository, channelGroupRepository, channelGroupMemberRepository,
    )
    private val operationEnvelopes = OperationConfigurationEnvelopes(limitConfigurationRepository, operationTypeRepository)

    override fun registerTenant(command: RegisterTenantCommand): TenantResult = mapValidation {
        val tenant = Tenant(
            id = TenantId.of(idGenerator.newId()),
            name = TenantName.of(command.name),
            config = Metadata(command.config),
            noMatchingRuleBehavior = NoMatchingRuleBehavior.valueOf(command.noMatchingRuleBehavior.name),
            createdAt = clockProvider.now(),
        )

        return tenantRepository.insert(tenant).toResult()
    }

    override fun registerIdentifierScheme(
        command: RegisterIdentifierSchemeCommand,
    ): IdentifierSchemeResult = mapValidation {
        val tenantId = TenantId.of(command.tenantId)
        requireTenantExists(tenantId)
        val scheme = IdentifierScheme(
            id = IdentifierSchemeId.of(idGenerator.newId()),
            tenantId = tenantId,
            entityType = EntityType.of(command.entityType),
            schemeName = SchemeName.of(command.schemeName),
            formatRegex = command.formatRegex,
            maxLength = command.maxLength,
            isPrimary = command.isPrimary,
            createdAt = clockProvider.now(),
        )

        return identifierSchemeRepository.insert(tenantId, scheme).toResult()
    }

    override fun assignEntityIdentifier(
        command: AssignEntityIdentifierCommand,
    ): EntityIdentifierResult = mapValidation {
        val tenantId = TenantId.of(command.tenantId)
        requireTenantExists(tenantId)
        val entityType = EntityType.of(command.entityType)
        val scheme = requireIdentifierScheme(
            tenantId = tenantId,
            entityType = entityType,
            schemeName = SchemeName.of(command.schemeName),
        )

        return attachEntityIdentifier(
            tenantId = tenantId,
            entityType = entityType,
            entityId = command.entityId,
            scheme = scheme,
            identifier = IdentifierValue.of(command.identifier),
            now = clockProvider.now(),
        ).toResult(scheme)
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

    override fun updateChannel(command: UpdateChannelCommand): ChannelResult = mapValidation {
        val tenantId = TenantId.of(command.tenantId)
        requireTenantExists(tenantId)

        transactionRunner.inTransaction {
            val channel = requireChannelForUpdate(tenantId, ChannelId.of(command.channelId))
            channelRepository.update(
                tenantId,
                channel.copy(
                    name = ChannelName.of(command.name),
                    metadata = Metadata(command.metadata),
                ),
            ).toResult()
        }
    }

    override fun setChannelActive(command: SetChannelActiveCommand): ChannelResult = mapValidation {
        val tenantId = TenantId.of(command.tenantId)
        requireTenantExists(tenantId)

        transactionRunner.inTransaction {
            val channel = requireChannelForUpdate(tenantId, ChannelId.of(command.channelId))
            if (channel.isActive == command.isActive) {
                channel.toResult()
            } else {
                channelRepository.update(
                    tenantId,
                    channel.copy(isActive = command.isActive),
                ).toResult()
            }
        }
    }

    override fun createChannelGroup(command: CreateChannelGroupCommand): ChannelGroupResult = mapValidation {
        val tenantId = TenantId.of(command.tenantId)
        requireTenantExists(tenantId)
        val group = ChannelGroup(
            id = ChannelGroupId.of(idGenerator.newId()),
            tenantId = tenantId,
            code = ChannelGroupCode.of(command.code),
            name = ChannelGroupName.of(command.name),
            metadata = Metadata(command.metadata),
            createdAt = clockProvider.now(),
        )

        return channelGroupRepository.insert(tenantId, group).toResult()
    }

    override fun addChannelGroupMember(
        command: AddChannelGroupMemberCommand,
    ): ChannelGroupMemberResult = mapValidation {
        val tenantId = TenantId.of(command.tenantId)
        requireTenantExists(tenantId)
        return transactionRunner.inTransaction {
            limitConfigurationRepository.lockFundingEnvelopes(tenantId)
            val groupId = ChannelGroupId.of(command.channelGroupId)
            val channelId = ChannelId.of(command.channelId)
            requireChannelGroupExists(tenantId, groupId)
            requireChannelExists(tenantId, channelId)
            val member = ChannelGroupMember(
                tenantId = tenantId,
                groupId = groupId,
                channelId = channelId,
                createdAt = clockProvider.now(),
            )
            channelEnvelopes.validateMembershipGrowth(member)

            channelGroupMemberRepository.insert(tenantId, member).toResult()
        }
    }

    override fun registerOperationType(command: RegisterOperationTypeCommand): OperationTypeResult = mapValidation {
        val tenantId = TenantId.of(command.tenantId)
        requireTenantExists(tenantId)
        val parentId = command.parentId?.let { OperationTypeId.of(it) }
        return transactionRunner.inTransaction {
            operationTypeRepository.lockCatalog()
            if (parentId != null) {
                val parentPath = OperationTypeAncestry(operationTypeRepository).resolve(tenantId, parentId)
                require(!command.isGlobal || parentPath.all { it.scope == OperationTypeScope.Global }) {
                    "Global operation types may have only global ancestors"
                }
                require(!parentPath.first().isLeaf) { "Cannot add a child beneath a declared operation leaf" }
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

            val affectedTenants = if (command.isGlobal) {
                tenantRepository.findPage(null, 0, Int.MAX_VALUE).map { it.id }.sortedBy { it.value }
            } else listOf(tenantId)
            affectedTenants.forEach { operationEnvelopes.validateOperationGrowth(it) }

            operationTypeRepository.insert(tenantId, operationType).toResult()
        }
    }

    override fun registerPartyProfile(command: RegisterPartyProfileCommand): PartyProfileResult = mapValidation {
        val tenantId = TenantId.of(command.tenantId)
        requireTenantExists(tenantId)
        val now = clockProvider.now()
        val newParty = Party(PartyId.of(idGenerator.newId()), command.partyType.toDomain(), now)
        val verifiedDetails = verifiedPartyDetails(command, newParty)
        val profileMetadata = Metadata(command.metadata)
        val legalIdentifierInputs = command.legalIdentifiers.map { legalIdentifier ->
            LegalIdentifierInput(
                jurisdictionCountry = JurisdictionCountry.of(legalIdentifier.jurisdictionCountry),
                identifierType = LegalIdentifierType.of(legalIdentifier.identifierType),
                normalizedIdentifier = LegalIdentifierValue.of(legalIdentifier.normalizedIdentifier),
                metadata = Metadata(legalIdentifier.metadata),
            )
        }
            .distinctBy { Triple(it.jurisdictionCountry, it.identifierType, it.normalizedIdentifier) }
        val externalIdentifierInputs = command.externalIdentifiers.map { externalIdentifier ->
            ExternalIdentifierInput(
                schemeName = SchemeName.of(externalIdentifier.schemeName),
                identifier = IdentifierValue.of(externalIdentifier.identifier),
            )
        }

        return try {
            transactionRunner.inTransaction {
                val resolvedParty = resolveParty(newParty, verifiedDetails, legalIdentifierInputs, now)
                val profile = PartyProfile(
                    id = PartyProfileId.of(idGenerator.newId()),
                    partyId = resolvedParty.id,
                    tenantId = tenantId,
                    createdAt = now,
                    metadata = profileMetadata,
                )

                val insertedProfile = partyProfileRepository.insert(tenantId, profile)
                val externalIdentifiers = externalIdentifierInputs
                    .map { input ->
                        val scheme = requireIdentifierScheme(
                            tenantId = tenantId,
                            entityType = EntityType.PARTY_PROFILE,
                            schemeName = input.schemeName,
                        )
                        val identifier = attachEntityIdentifier(
                            tenantId = tenantId,
                            entityType = EntityType.PARTY_PROFILE,
                            entityId = insertedProfile.id.value,
                            scheme = scheme,
                            identifier = input.identifier,
                            now = now,
                        )
                        identifier.toResult(scheme)
                    }
                    .distinctBy { it.id }
                insertedProfile.toResult(externalIdentifiers = externalIdentifiers)
            }
        } catch (exception: ConflictException) {
            // SQL causes can contain shared legal identifiers; they must not cross onboarding.
            throw ConflictException("party profile registration conflicts with existing data")
        } catch (exception: InfrastructureException) {
            throw InfrastructureException("party profile registration failed")
        }
    }

    override fun registerBankWorker(command: RegisterBankWorkerCommand): BankWorkerResult = mapValidation {
        val tenantId = TenantId.of(command.tenantId)
        requireTenantExists(tenantId)
        val bankWorker = BankWorker(
            id = BankWorkerId.of(idGenerator.newId()),
            tenantId = tenantId,
            externalReference = ExternalReference.of(command.externalReference),
            displayName = DisplayName.of(command.displayName),
            branchCode = command.branchCode?.let(BranchCode::of),
            isActive = command.isActive,
            metadata = Metadata(command.metadata),
            createdAt = clockProvider.now(),
        )

        return bankWorkerRepository.insert(tenantId, bankWorker).toResult()
    }

    override fun createPartyProfileDelegation(
        command: CreatePartyProfileDelegationCommand,
    ): PartyProfileDelegationResult = mapValidation {
        val tenantId = TenantId.of(command.tenantId)
        requireTenantExists(tenantId)
        val ownerPartyProfileId = PartyProfileId.of(command.ownerPartyProfileId)
        val delegatePartyProfileId = PartyProfileId.of(command.delegatePartyProfileId)
        requirePartyProfileExists(tenantId, ownerPartyProfileId)
        requirePartyProfileExists(tenantId, delegatePartyProfileId)
        val now = clockProvider.now()
        val delegation = PartyProfileDelegation(
            id = PartyProfileDelegationId.of(idGenerator.newId()),
            tenantId = tenantId,
            ownerPartyProfileId = ownerPartyProfileId,
            delegatePartyProfileId = delegatePartyProfileId,
            delegationType = PartyProfileDelegationType.valueOf(command.delegationType.name),
            effectiveWindow = EffectiveWindow(command.startsAt ?: now, command.endsAt),
            metadata = Metadata(command.metadata),
            createdAt = now,
        )

        return partyProfileDelegationRepository.insert(tenantId, delegation).toResult()
    }

    override fun registerFundingSourceProduct(command: RegisterFundingSourceProductCommand): FundingSourceProductResult = mapValidation {
        val tenantId = TenantId.of(command.tenantId)
        requireTenantExists(tenantId)
        val parentId = command.parentId?.let { FundingSourceProductId.of(it) }

        return transactionRunner.inTransaction {
            limitConfigurationRepository.lockFundingEnvelopes(tenantId)
            if (parentId != null) {
                val parent = requireFundingSourceProductExists(tenantId, parentId)
                require(!parent.isLeaf) {
                    "Cannot add a child beneath a declared funding source product leaf: ${parent.id.value}"
                }
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

            fundingEnvelopes.validateProductGrowth(fundingSourceProduct)
            fundingSourceProductRepository.insert(tenantId, fundingSourceProduct).toResult()
        }
    }

    override fun updateFundingSourceProduct(
        command: UpdateFundingSourceProductCommand,
    ): FundingSourceProductResult = mapValidation {
        val tenantId = TenantId.of(command.tenantId)
        requireTenantExists(tenantId)

        transactionRunner.inTransaction {
            val product = requireFundingSourceProductForUpdate(
                tenantId,
                FundingSourceProductId.of(command.fundingSourceProductId),
            )
            fundingSourceProductRepository.update(
                tenantId,
                product.copy(
                    name = FundingSourceProductName.of(command.name),
                    metadata = Metadata(command.metadata),
                ),
            ).toResult()
        }
    }

    override fun setFundingSourceProductActive(
        command: SetFundingSourceProductActiveCommand,
    ): FundingSourceProductResult = mapValidation {
        val tenantId = TenantId.of(command.tenantId)
        requireTenantExists(tenantId)

        transactionRunner.inTransaction {
            val product = requireFundingSourceProductForUpdate(
                tenantId,
                FundingSourceProductId.of(command.fundingSourceProductId),
            )
            if (product.isActive == command.isActive) {
                product.toResult()
            } else {
                fundingSourceProductRepository.update(
                    tenantId,
                    product.copy(isActive = command.isActive),
                ).toResult()
            }
        }
    }

    override fun registerFundingSource(command: RegisterFundingSourceCommand): FundingSourceResult = mapValidation {
        val tenantId = TenantId.of(command.tenantId)
        requireTenantExists(tenantId)
        val productId = FundingSourceProductId.of(command.productId)

        return transactionRunner.inTransaction {
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

            fundingSourceRepository.insert(tenantId, fundingSource).toResult()
        }
    }

    override fun updateFundingSource(command: UpdateFundingSourceCommand): FundingSourceResult = mapValidation {
        val tenantId = TenantId.of(command.tenantId)
        requireTenantExists(tenantId)

        transactionRunner.inTransaction {
            val fundingSource = requireFundingSourceForUpdate(
                tenantId,
                FundingSourceId.of(command.fundingSourceId),
            )
            fundingSourceRepository.update(
                tenantId,
                fundingSource.copy(
                    externalReference = command.externalReference?.let(ExternalReference::of),
                    metadata = Metadata(command.metadata),
                ),
            ).toResult()
        }
    }

    override fun setFundingSourceActive(
        command: SetFundingSourceActiveCommand,
    ): FundingSourceResult = mapValidation {
        val tenantId = TenantId.of(command.tenantId)
        requireTenantExists(tenantId)

        transactionRunner.inTransaction {
            val fundingSource = requireFundingSourceForUpdate(
                tenantId,
                FundingSourceId.of(command.fundingSourceId),
            )
            if (fundingSource.isActive == command.isActive) {
                fundingSource.toResult()
            } else {
                fundingSourceRepository.update(
                    tenantId,
                    fundingSource.copy(isActive = command.isActive),
                ).toResult()
            }
        }
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

    override fun createFundingSourceGroup(
        command: CreateFundingSourceGroupCommand,
    ): FundingSourceGroupResult = mapValidation {
        val tenantId = TenantId.of(command.tenantId)
        requireTenantExists(tenantId)
        val ownerPartyProfileId = PartyProfileId.of(command.ownerPartyProfileId)
        requirePartyProfileExists(tenantId, ownerPartyProfileId)
        val group = FundingSourceGroup(
            id = FundingSourceGroupId.of(idGenerator.newId()),
            tenantId = tenantId,
            ownerPartyProfileId = ownerPartyProfileId,
            code = FundingSourceGroupCode.of(command.code),
            name = FundingSourceGroupName.of(command.name),
            metadata = Metadata(command.metadata),
            createdAt = clockProvider.now(),
        )

        return fundingSourceGroupRepository.insert(tenantId, group).toResult()
    }

    override fun addFundingSourceGroupMember(
        command: AddFundingSourceGroupMemberCommand,
    ): FundingSourceGroupMemberResult = mapValidation {
        val tenantId = TenantId.of(command.tenantId)
        requireTenantExists(tenantId)
        val groupId = FundingSourceGroupId.of(command.fundingSourceGroupId)
        val fundingSourceId = FundingSourceId.of(command.fundingSourceId)
        val group = requireFundingSourceGroupExists(tenantId, groupId)
        requireFundingSourceOwnedBy(tenantId, group.ownerPartyProfileId, fundingSourceId)
        val member = FundingSourceGroupMember(
            tenantId = tenantId,
            groupId = groupId,
            ownerPartyProfileId = group.ownerPartyProfileId,
            fundingSourceId = fundingSourceId,
            createdAt = clockProvider.now(),
        )

        return fundingSourceGroupMemberRepository.insert(tenantId, member).toResult()
    }

    override fun createMandate(command: CreateMandateCommand): MandateResult = mapValidation {
        val tenantId = TenantId.of(command.tenantId)
        requireTenantExists(tenantId)
        val ownerPartyProfileId = PartyProfileId.of(command.ownerPartyProfileId)
        val assigneePartyProfileId = PartyProfileId.of(command.assigneePartyProfileId)
        requirePartyProfileExists(tenantId, ownerPartyProfileId)
        requirePartyProfileExists(tenantId, assigneePartyProfileId)
        val now = clockProvider.now()
        val authorityDelegation = command.authorityDelegationId?.let { delegationId ->
            val id = PartyProfileDelegationId.of(delegationId)
            val delegation = requirePartyProfileDelegationExists(tenantId, id)
            if (delegation.ownerPartyProfileId != ownerPartyProfileId ||
                delegation.delegatePartyProfileId != assigneePartyProfileId
            ) {
                throw ConflictException(
                    "authority delegation does not connect the mandate owner and assignee: ${id.value}"
                )
            }
            delegation
        }
        val effectiveWindow = EffectiveWindow(
            command.startsAt ?: now,
            command.endsAt
        )
        if (authorityDelegation != null && !authorityDelegation.effectiveWindow.contains(effectiveWindow)) {
            throw ValidationException("mandate effective window must be contained by its authority delegation")
        }
        val mandate = Mandate(
            id = MandateId.of(idGenerator.newId()),
            tenantId = tenantId,
            ownerPartyProfileId = ownerPartyProfileId,
            assigneePartyProfileId = assigneePartyProfileId,
            authorityDelegationId = authorityDelegation?.id,
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
                    val fundingSourceGroupId = FundingSourceGroupId.of(
                        command.fundingSourceGroupId
                            ?: throw ValidationException("FundingSourceGroupId must not be null for LimitFundingScope.FUNDING_SOURCE_GROUP"),
                    )
                    val group = requireFundingSourceGroupExists(tenantId, fundingSourceGroupId)
                    if (group.ownerPartyProfileId != ownerPartyProfileId) {
                        throw ConflictException(
                            "funding source group is owned by another party profile: ${fundingSourceGroupId.value}"
                        )
                    }
                    FundingSourceScope.FundingSourceGroupSpecific(fundingSourceGroupId)
                }
            },
            isActive = true,
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
        return transactionRunner.inTransaction {
            operationTypeRepository.lockCatalog()
            limitConfigurationRepository.lockFundingEnvelopes(tenantId)
            val parentConfigurationId = command.parentConfigurationId?.let { LimitConfigurationId.of(it) }
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
                        val channelGroupId = ChannelGroupId.of(
                            command.channelGroupId
                                ?: throw ValidationException("ChannelGroupId must not be null for LimitChannelScope.CHANNEL_GROUP"),
                        )
                        requireChannelGroupExists(tenantId, channelGroupId)
                        DomainLimitChannelScope.ChannelGroupSpecific(channelGroupId)
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
            fundingEnvelopes.validateHierarchy(limitConfiguration)
            channelEnvelopes.validateHierarchy(limitConfiguration)
            operationEnvelopes.validateHierarchy(limitConfiguration)

            limitConfigurationRepository.insert(tenantId, limitConfiguration).toResult()
        }
    }

    override fun updateLimitConfiguration(
        command: UpdateLimitConfigurationCommand,
    ): LimitConfigurationResult = mapValidation {
        val tenantId = TenantId.of(command.tenantId)
        requireTenantExists(tenantId)

        transactionRunner.inTransaction {
            operationTypeRepository.lockCatalog()
            limitConfigurationRepository.lockFundingEnvelopes(tenantId)
            val configuration = requireLimitConfigurationForUpdate(
                tenantId,
                LimitConfigurationId.of(command.limitConfigurationId),
            )
            val updated = configuration.copy(
                name = LimitConfigurationName.of(command.name),
                description = command.description,
                defaultAmount = command.defaultAmount?.let {
                    Money.of(it, configuration.maxAssignableAmount.currency)
                },
                metadata = Metadata(command.metadata),
                updatedAt = clockProvider.now(),
            )
            fundingEnvelopes.validateHierarchy(updated)
            channelEnvelopes.validateHierarchy(updated)
            operationEnvelopes.validateHierarchy(updated)

            limitConfigurationRepository.update(tenantId, updated).toResult()
        }
    }

    override fun setLimitConfigurationActive(
        command: SetLimitConfigurationActiveCommand,
    ): LimitConfigurationResult = mapValidation {
        val tenantId = TenantId.of(command.tenantId)
        requireTenantExists(tenantId)

        transactionRunner.inTransaction {
            operationTypeRepository.lockCatalog()
            limitConfigurationRepository.lockFundingEnvelopes(tenantId)
            val configuration = requireLimitConfigurationForUpdate(
                tenantId,
                LimitConfigurationId.of(command.limitConfigurationId),
            )
            fundingEnvelopes.validateHierarchy(configuration)
            channelEnvelopes.validateHierarchy(configuration)
            operationEnvelopes.validateHierarchy(configuration)
            if (configuration.isActive == command.isActive) {
                configuration.toResult()
            } else {
                limitConfigurationRepository.update(
                    tenantId,
                    configuration.copy(
                        isActive = command.isActive,
                        updatedAt = clockProvider.now(),
                    ),
                ).toResult()
            }
        }
    }

    override fun createLimitChangePolicy(
        command: CreateLimitChangePolicyCommand,
    ): LimitChangePolicyResult = mapValidation {
        val tenantId = TenantId.of(command.tenantId)
        requireTenantExists(tenantId)
        val phase = LimitChangePolicyPhase.valueOf(command.phase.name)
        val decision = LimitChangePolicyDecision.valueOf(command.decision.name)
        if (phase != LimitChangePolicyPhase.AUTHORIZATION) {
            throw ValidationException(
                "limit change policy phase ${phase.name} is not supported; only AUTHORIZATION is supported",
            )
        }
        if (decision !in LimitChangePolicyCapabilities.authorizationDecisions) {
            throw ValidationException(
                "limit change policy decision ${decision.name} is not supported during AUTHORIZATION",
            )
        }
        val limitConfigurationId = command.limitConfigurationId?.let { rawId ->
            val id = LimitConfigurationId.of(rawId)
            requireLimitConfigurationExists(tenantId, id)
            id
        }
        val now = clockProvider.now()
        val policy = LimitChangePolicy(
            id = LimitChangePolicyId.of(idGenerator.newId()),
            tenantId = tenantId,
            code = LimitChangePolicyCode.of(command.code),
            name = LimitChangePolicyName.of(command.name),
            description = command.description,
            phase = phase,
            engine = LimitChangePolicyEngine.valueOf(command.engine.name),
            decision = decision,
            limitConfigurationId = limitConfigurationId,
            priority = LimitPriority.of(command.priority),
            isActive = command.isActive,
            appliesTo = Metadata(command.appliesTo),
            conditionExpression = Metadata(command.conditionExpression),
            actionExpression = JsonSnapshots.objectList(command.actionExpression, "actionExpression"),
            denialCode = command.denialCode,
            denialMessage = command.denialMessage,
            effectiveWindow = EffectiveWindow(command.startsAt ?: now, command.endsAt),
            metadata = Metadata(command.metadata),
            createdAt = now,
            updatedAt = now,
        )
        val policyEngine = policyEngines.firstOrNull { it.engine == policy.engine }
            ?: throw ValidationException("no policy engine registered for ${policy.engine.name}")
        policyEngine.validate(policy)

        return limitChangePolicyRepository.insert(tenantId, policy).toResult()
    }

    private fun resolveParty(
        newParty: Party,
        verifiedDetails: VerifiedPartyDetails,
        legalIdentifierInputs: List<LegalIdentifierInput>,
        now: Instant,
    ): Party {
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
            if (matchingIdentifiers.size != legalIdentifierInputs.size) {
                throw ConflictException("new legal identifiers require controlled identity maintenance")
            }
            val party = partyRepository.findById(matchingPartyIds.single())
                ?: throw ConflictException("legal identifier points to a missing party")
            requirePartyType(party, newParty.partyType)
            return party
        }

        val party = partyRepository.insert(newParty)
        verifiedDetails.individual?.let { individualRepository.insert(it) }
        verifiedDetails.legalEntity?.let { legalEntityRepository.insert(it) }
        insertLegalIdentifiers(party.id, legalIdentifierInputs, now)

        return party
    }

    private fun verifiedPartyDetails(
        command: RegisterPartyProfileCommand,
        party: Party,
    ): VerifiedPartyDetails =
        when (party.partyType) {
            DomainPartyType.INDIVIDUAL -> VerifiedPartyDetails(
                individual = Individual(
                    partyId = party.id,
                    firstName = FirstName.of(
                        command.firstName ?: throw ValidationException("firstName is required for INDIVIDUAL parties")
                    ),
                    lastName = LastName.of(
                        command.lastName ?: throw ValidationException("lastName is required for INDIVIDUAL parties")
                    ),
                    birthDate = command.birthDate,
                )
            )
            DomainPartyType.LEGAL_ENTITY -> VerifiedPartyDetails(
                legalEntity = LegalEntity(
                    partyId = party.id,
                    legalName = LegalName.of(
                        command.legalName ?: throw ValidationException("legalName is required for LEGAL_ENTITY parties")
                    ),
                    legalForm = command.legalForm?.let { LegalForm.of(it) },
                )
            )
        }

    private fun insertLegalIdentifiers(
        partyId: PartyId,
        legalIdentifierInputs: List<LegalIdentifierInput>,
        now: Instant,
    ) {
        legalIdentifierInputs.forEach { input ->
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

    private fun attachEntityIdentifier(
        tenantId: TenantId,
        entityType: EntityType,
        entityId: java.util.UUID,
        scheme: IdentifierScheme,
        identifier: IdentifierValue,
        now: Instant,
    ): EntityIdentifier {
        when (entityType) {
            EntityType.PARTY_PROFILE -> requirePartyProfileExists(tenantId, PartyProfileId.of(entityId))
            EntityType.FUNDING_SOURCE -> requireFundingSourceExists(tenantId, FundingSourceId.of(entityId))
            else -> Unit
        }
        validateIdentifier(scheme, identifier)

        val existing = entityIdentifierRepository.findByValue(tenantId, scheme.id, identifier)
        if (existing != null) {
            if (existing.entityId != entityId) {
                throw ConflictException(
                    "entity identifier belongs to another entity: ${scheme.schemeName.value}/${identifier.value}"
                )
            }
            return existing
        }

        return entityIdentifierRepository.insert(
            tenantId,
            EntityIdentifier(
                id = EntityIdentifierId.of(idGenerator.newId()),
                entityType = entityType,
                entityId = entityId,
                schemeId = scheme.id,
                identifier = identifier,
                createdAt = now,
            )
        )
    }

    private fun validateIdentifier(scheme: IdentifierScheme, identifier: IdentifierValue) {
        if (scheme.maxLength != null && identifier.value.length > scheme.maxLength) {
            throw ValidationException(
                "identifier exceeds the maximum length for scheme ${scheme.schemeName.value}"
            )
        }
        if (scheme.formatRegex != null && !Regex(scheme.formatRegex).matches(identifier.value)) {
            throw ValidationException(
                "identifier does not match the format for scheme ${scheme.schemeName.value}"
            )
        }
    }

    private fun requirePartyType(party: Party, expected: DomainPartyType) {
        if (party.partyType != expected) {
            throw ConflictException("party type does not match verified onboarding details")
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

    private fun requireIdentifierScheme(
        tenantId: TenantId,
        entityType: EntityType,
        schemeName: SchemeName,
    ): IdentifierScheme =
        identifierSchemeRepository.findByName(tenantId, entityType, schemeName)
            ?: throw NotFoundException(
                "identifier scheme not found: ${entityType.value}/${schemeName.value}"
            )

    private fun requireChannelExists(tenantId: TenantId, channelId: ChannelId): Channel =
        channelRepository.findById(tenantId, channelId)
            ?: throw NotFoundException("channel not found: ${channelId.value}")

    private fun requireChannelForUpdate(tenantId: TenantId, channelId: ChannelId): Channel =
        channelRepository.findByIdForUpdate(tenantId, channelId)
            ?: throw NotFoundException("channel not found: ${channelId.value}")

    private fun requireChannelGroupExists(tenantId: TenantId, groupId: ChannelGroupId): ChannelGroup =
        channelGroupRepository.findById(tenantId, groupId)
            ?: throw NotFoundException("channel group not found: ${groupId.value}")

    private fun requireOperationTypeVisible(tenantId: TenantId, operationTypeId: OperationTypeId): OperationType =
        operationTypeRepository.findById(tenantId, operationTypeId)
            ?: throw NotFoundException("operation type not found: ${operationTypeId.value}")

    private fun requirePartyProfileExists(tenantId: TenantId, partyProfileId: PartyProfileId): PartyProfile =
        partyProfileRepository.findById(tenantId, partyProfileId)
            ?: throw NotFoundException("party profile not found: ${partyProfileId.value}")

    private fun requirePartyProfileDelegationExists(
        tenantId: TenantId,
        delegationId: PartyProfileDelegationId,
    ): PartyProfileDelegation =
        partyProfileDelegationRepository.findById(tenantId, delegationId)
            ?: throw NotFoundException("party profile delegation not found: ${delegationId.value}")

    private fun requireFundingSourceProductExists(
        tenantId: TenantId,
        productId: FundingSourceProductId,
    ): FundingSourceProduct =
        fundingSourceProductRepository.findById(tenantId, productId)
            ?: throw NotFoundException("funding source product not found: ${productId.value}")

    private fun requireFundingSourceProductForUpdate(
        tenantId: TenantId,
        productId: FundingSourceProductId,
    ): FundingSourceProduct =
        fundingSourceProductRepository.findByIdForUpdate(tenantId, productId)
            ?: throw NotFoundException("funding source product not found: ${productId.value}")

    private fun requireFundingSourceExists(tenantId: TenantId, fundingSourceId: FundingSourceId): FundingSource =
        fundingSourceRepository.findById(tenantId, fundingSourceId)
            ?: throw NotFoundException("funding source not found: ${fundingSourceId.value}")

    private fun requireFundingSourceForUpdate(
        tenantId: TenantId,
        fundingSourceId: FundingSourceId,
    ): FundingSource =
        fundingSourceRepository.findByIdForUpdate(tenantId, fundingSourceId)
            ?: throw NotFoundException("funding source not found: ${fundingSourceId.value}")

    private fun requireFundingSourceGroupExists(
        tenantId: TenantId,
        groupId: FundingSourceGroupId,
    ): FundingSourceGroup =
        fundingSourceGroupRepository.findById(tenantId, groupId)
            ?: throw NotFoundException("funding source group not found: ${groupId.value}")

    private fun requireLimitConfigurationExists(
        tenantId: TenantId,
        configurationId: LimitConfigurationId,
    ): LimitConfiguration =
        limitConfigurationRepository.findById(tenantId, configurationId)
            ?: throw NotFoundException("limit configuration not found: ${configurationId.value}")

    private fun requireLimitConfigurationForUpdate(
        tenantId: TenantId,
        configurationId: LimitConfigurationId,
    ): LimitConfiguration =
        limitConfigurationRepository.findByIdForUpdate(tenantId, configurationId)
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

    private data class ExternalIdentifierInput(
        val schemeName: SchemeName,
        val identifier: IdentifierValue,
    )

    private data class VerifiedPartyDetails(
        val individual: Individual? = null,
        val legalEntity: LegalEntity? = null,
    )

}
