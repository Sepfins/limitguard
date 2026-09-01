package io.github.sepfins.limitguard.application.catalog

import io.github.sepfins.limitguard.api.FundingSourceCategory
import io.github.sepfins.limitguard.api.FundingSourceOwnerRole
import io.github.sepfins.limitguard.api.LimitChannelScope
import io.github.sepfins.limitguard.api.LimitConfigurationFundingScope
import io.github.sepfins.limitguard.api.LimitFundingScope
import io.github.sepfins.limitguard.api.LimitKind
import io.github.sepfins.limitguard.api.LimitPeriod
import io.github.sepfins.limitguard.api.PartyType
import io.github.sepfins.limitguard.api.ProductNodeType
import io.github.sepfins.limitguard.api.commands.AssignFundingSourceOwnerCommand
import io.github.sepfins.limitguard.api.commands.CreateLimitConfigurationCommand
import io.github.sepfins.limitguard.api.commands.CreateMandateCommand
import io.github.sepfins.limitguard.api.commands.PartyLegalIdentifierCommand
import io.github.sepfins.limitguard.api.commands.RegisterChannelCommand
import io.github.sepfins.limitguard.api.commands.RegisterFundingSourceCommand
import io.github.sepfins.limitguard.api.commands.RegisterFundingSourceProductCommand
import io.github.sepfins.limitguard.api.commands.RegisterOperationTypeCommand
import io.github.sepfins.limitguard.api.commands.RegisterPartyProfileCommand
import io.github.sepfins.limitguard.api.commands.RegisterTenantCommand
import io.github.sepfins.limitguard.api.errors.ConflictException
import io.github.sepfins.limitguard.api.errors.NotFoundException
import io.github.sepfins.limitguard.api.errors.ValidationException
import io.github.sepfins.limitguard.domain.common.MandateId
import io.github.sepfins.limitguard.domain.common.Metadata
import io.github.sepfins.limitguard.domain.common.PartyId
import io.github.sepfins.limitguard.domain.common.TenantId
import io.github.sepfins.limitguard.domain.party.FirstName
import io.github.sepfins.limitguard.domain.party.Individual
import io.github.sepfins.limitguard.domain.party.LastName
import io.github.sepfins.limitguard.domain.party.Party
import io.github.sepfins.limitguard.domain.tenant.Tenant
import io.github.sepfins.limitguard.domain.tenant.TenantName
import io.github.sepfins.limitguard.support.DirectTransactionRunner
import io.github.sepfins.limitguard.support.FixedClockProvider
import io.github.sepfins.limitguard.support.SequentialIdGenerator
import io.github.sepfins.limitguard.support.persistence.InMemoryLimitguardRepositories
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import io.github.sepfins.limitguard.domain.party.PartyType as DomainPartyType

class DefaultTenantCatalogServiceTest {
    private val tenantOne = UUID(0L, 101L)
    private val tenantTwo = UUID(0L, 102L)
    private val fixedNow = Instant.parse("2026-06-21T00:00:00Z")
    private val codiceFiscale = PartyLegalIdentifierCommand(
        jurisdictionCountry = "IT",
        identifierType = "CODICE_FISCALE",
        normalizedIdentifier = "RSSMRA80A01H501U",
    )

    @Test
    fun `register tenant creates tenant`() {
        val repositories = InMemoryLimitguardRepositories()
        val service = catalogService(repositories)

        val result = service.registerTenant(
            RegisterTenantCommand(
                name = "Retail Bank",
                config = mapOf("country" to "IT"),
            )
        )

        assertEquals("Retail Bank", result.name)
        assertEquals(mapOf("country" to "IT"), result.config)
        assertEquals(fixedNow, result.createdAt)
        assertTrue(repositories.tenants.exists(TenantId.of(result.id)))
    }

    @Test
    fun `register tenant rejects duplicate name`() {
        val repositories = InMemoryLimitguardRepositories()
        val service = catalogService(repositories)

        service.registerTenant(RegisterTenantCommand(name = "Retail Bank"))

        assertFailsWith<ConflictException> {
            service.registerTenant(RegisterTenantCommand(name = "Retail Bank"))
        }
    }

    @Test
    fun `register channel creates tenant scoped channel`() {
        val repositories = InMemoryLimitguardRepositories()
        seedTenant(repositories, tenantOne)
        val service = catalogService(repositories)

        val result = service.registerChannel(
            RegisterChannelCommand(
                tenantId = tenantOne,
                code = "ATM",
                name = "ATM",
                metadata = mapOf("network" to "domestic"),
            )
        )

        assertEquals(tenantOne, result.tenantId)
        assertEquals("ATM", result.code)
        assertEquals("ATM", result.name)
        assertEquals(mapOf("network" to "domestic"), result.metadata)
    }

    @Test
    fun `register channel rejects missing tenant`() {
        val repositories = InMemoryLimitguardRepositories()
        val service = catalogService(repositories)

        assertFailsWith<NotFoundException> {
            service.registerChannel(
                RegisterChannelCommand(
                    tenantId = tenantOne,
                    code = "ATM",
                    name = "ATM",
                )
            )
        }
    }

    @Test
    fun `register party profile creates party when legal identifier is new`() {
        val repositories = InMemoryLimitguardRepositories()
        seedTenant(repositories, tenantOne)
        val service = catalogService(repositories)

        val result = service.registerPartyProfile(individualCommand(tenantOne))

        assertTrue(result.partyCreated)
        assertEquals(1, repositories.parties.count())
        assertEquals(1, repositories.partyLegalIdentifiers.findByPartyId(PartyId.of(result.partyId)).size)
        assertEquals("Mario", result.firstName)
        assertEquals("Rossi", result.lastName)
    }

    @Test
    fun `register party profile reuses party found by legal identifier across tenants`() {
        val repositories = InMemoryLimitguardRepositories()
        seedTenant(repositories, tenantOne)
        seedTenant(repositories, tenantTwo)
        val service = catalogService(repositories)

        val first = service.registerPartyProfile(individualCommand(tenantOne))
        val second = service.registerPartyProfile(individualCommand(tenantTwo))

        assertTrue(first.partyCreated)
        assertFalse(second.partyCreated)
        assertEquals(first.partyId, second.partyId)
        assertEquals(1, repositories.parties.count())
        assertEquals(1, repositories.partyProfiles.findByPartyId(TenantId.of(tenantOne), PartyId.of(first.partyId)).size)
        assertEquals(1, repositories.partyProfiles.findByPartyId(TenantId.of(tenantTwo), PartyId.of(second.partyId)).size)
    }

    @Test
    fun `register party profile can link an explicit existing party`() {
        val repositories = InMemoryLimitguardRepositories()
        seedTenant(repositories, tenantOne)
        val service = catalogService(repositories)
        val existingPartyId = PartyId.of(UUID(0L, 900L))
        repositories.parties.insert(
            Party(
                id = existingPartyId,
                partyType = DomainPartyType.INDIVIDUAL,
                createdAt = Instant.parse("2026-06-20T00:00:00Z"),
            )
        )
        repositories.individuals.insert(
            Individual(
                partyId = existingPartyId,
                firstName = FirstName.of("Mario"),
                lastName = LastName.of("Rossi"),
                birthDate = LocalDate.parse("1980-01-01"),
            )
        )

        val result = service.registerPartyProfile(
            RegisterPartyProfileCommand(
                tenantId = tenantOne,
                partyType = PartyType.INDIVIDUAL,
                partyId = existingPartyId.value,
                createPartyIfMissing = false,
            )
        )

        assertFalse(result.partyCreated)
        assertEquals(existingPartyId.value, result.partyId)
        assertEquals(1, repositories.parties.count())
        assertEquals("Mario", result.firstName)
    }

    @Test
    fun `register party profile can require an existing party match`() {
        val repositories = InMemoryLimitguardRepositories()
        seedTenant(repositories, tenantOne)
        val service = catalogService(repositories)

        assertFailsWith<NotFoundException> {
            service.registerPartyProfile(
                individualCommand(
                    tenantId = tenantOne,
                    createPartyIfMissing = false,
                    legalIdentifier = codiceFiscale.copy(normalizedIdentifier = "BNCLRA85C45F205Z"),
                )
            )
        }
    }

    @Test
    fun `register operation type keeps command tenant scope`() {
        val repositories = InMemoryLimitguardRepositories()
        seedTenant(repositories, tenantOne)
        val service = catalogService(repositories)

        val result = service.registerOperationType(
            RegisterOperationTypeCommand(
                tenantId = tenantOne,
                code = "WIRE_TRANSFER",
                name = "Wire transfer",
                isLeaf = true,
            )
        )

        assertEquals(tenantOne, result.tenantId)
        assertFalse(result.isGlobal)
        assertEquals("WIRE_TRANSFER", result.code)
    }

    @Test
    fun `register operation type can create global operation from tenant context`() {
        val repositories = InMemoryLimitguardRepositories()
        seedTenant(repositories, tenantOne)
        val service = catalogService(repositories)

        val result = service.registerOperationType(
            RegisterOperationTypeCommand(
                tenantId = tenantOne,
                code = "CARD_PAYMENT",
                name = "Card payment",
                isGlobal = true,
            )
        )

        assertEquals(null, result.tenantId)
        assertTrue(result.isGlobal)
        assertEquals("CARD_PAYMENT", result.code)
    }

    @Test
    fun `register operation type rejects missing parent`() {
        val repositories = InMemoryLimitguardRepositories()
        seedTenant(repositories, tenantOne)
        val service = catalogService(repositories)

        assertFailsWith<NotFoundException> {
            service.registerOperationType(
                RegisterOperationTypeCommand(
                    tenantId = tenantOne,
                    parentId = UUID(0L, 999L),
                    code = "WIRE_TRANSFER",
                    name = "Wire transfer",
                )
            )
        }
    }

    @Test
    fun `register funding source catalog returns metadata and ownership`() {
        val repositories = InMemoryLimitguardRepositories()
        seedTenant(repositories, tenantOne)
        val service = catalogService(repositories)
        val productMetadata = mapOf("catalog" to "retail")
        val fundingSourceMetadata = mapOf("ibanCountry" to "IT")
        val profile = createIndividualProfile(service, tenantOne, "OWNER001")

        val product = service.registerFundingSourceProduct(
            RegisterFundingSourceProductCommand(
                tenantId = tenantOne,
                code = "CC1000",
                name = "Retail current account",
                category = FundingSourceCategory.TRANSACTIONAL,
                nodeType = ProductNodeType.PRODUCT,
                isLeaf = true,
                metadata = productMetadata,
            )
        )
        val fundingSource = service.registerFundingSource(
            RegisterFundingSourceCommand(
                tenantId = tenantOne,
                productId = product.id,
                externalReference = "ACC-001",
                metadata = fundingSourceMetadata,
            )
        )
        val owner = service.assignFundingSourceOwner(
            AssignFundingSourceOwnerCommand(
                tenantId = tenantOne,
                partyProfileId = profile.id,
                fundingSourceId = fundingSource.id,
                ownerRole = FundingSourceOwnerRole.JOINT_OWNER,
            )
        )

        assertEquals(productMetadata, product.metadata)
        assertEquals(fundingSourceMetadata, fundingSource.metadata)
        assertEquals(FundingSourceOwnerRole.JOINT_OWNER, owner.ownerRole)
        assertEquals(fundingSource.id, owner.fundingSourceId)
    }

    @Test
    fun `register funding source product rejects missing parent`() {
        val repositories = InMemoryLimitguardRepositories()
        seedTenant(repositories, tenantOne)
        val service = catalogService(repositories)

        assertFailsWith<NotFoundException> {
            service.registerFundingSourceProduct(
                RegisterFundingSourceProductCommand(
                    tenantId = tenantOne,
                    parentId = UUID(0L, 999L),
                    code = "CC1000",
                    name = "Retail current account",
                    category = FundingSourceCategory.TRANSACTIONAL,
                    nodeType = ProductNodeType.PRODUCT,
                )
            )
        }
    }

    @Test
    fun `register funding source rejects missing product`() {
        val repositories = InMemoryLimitguardRepositories()
        seedTenant(repositories, tenantOne)
        val service = catalogService(repositories)

        assertFailsWith<NotFoundException> {
            service.registerFundingSource(
                RegisterFundingSourceCommand(
                    tenantId = tenantOne,
                    productId = UUID(0L, 999L),
                )
            )
        }
    }

    @Test
    fun `assign funding source owner rejects missing party profile`() {
        val repositories = InMemoryLimitguardRepositories()
        seedTenant(repositories, tenantOne)
        val service = catalogService(repositories)
        val product = createLeafProduct(service, tenantOne)
        val fundingSource = createFundingSource(service, tenantOne, product.id)

        assertFailsWith<NotFoundException> {
            service.assignFundingSourceOwner(
                AssignFundingSourceOwnerCommand(
                    tenantId = tenantOne,
                    partyProfileId = UUID(0L, 999L),
                    fundingSourceId = fundingSource.id,
                )
            )
        }
    }

    @Test
    fun `create mandate returns scoped result`() {
        val repositories = InMemoryLimitguardRepositories()
        seedTenant(repositories, tenantOne)
        val service = catalogService(repositories)
        val ownerProfile = createIndividualProfile(service, tenantOne, "OWNER002")
        val assigneeProfile = createIndividualProfile(service, tenantOne, "ASSIGNEE002")
        val product = createLeafProduct(service, tenantOne)
        val fundingSource = createFundingSource(service, tenantOne, product.id)
        service.assignFundingSourceOwner(
            AssignFundingSourceOwnerCommand(
                tenantId = tenantOne,
                partyProfileId = ownerProfile.id,
                fundingSourceId = fundingSource.id,
            )
        )
        val startsAt = Instant.parse("2026-07-01T00:00:00Z")

        val result = service.createMandate(
            CreateMandateCommand(
                tenantId = tenantOne,
                ownerPartyProfileId = ownerProfile.id,
                assigneePartyProfileId = assigneeProfile.id,
                name = "Family payment delegate",
                fundingScope = LimitFundingScope.FUNDING_SOURCE,
                fundingSourceId = fundingSource.id,
                startsAt = startsAt,
                metadata = mapOf("reason" to "family"),
            )
        )

        assertEquals(LimitFundingScope.FUNDING_SOURCE, result.fundingScope)
        assertEquals(fundingSource.id, result.fundingSourceId)
        assertEquals(null, result.fundingSourceGroupId)
        assertFalse(result.isActive)
        assertEquals(startsAt, result.startsAt)
        assertEquals(result.createdAt, result.updatedAt)
        assertEquals(mapOf("reason" to "family"), result.metadata)
    }

    @Test
    fun `create mandate rejects source not owned by owner profile`() {
        val repositories = InMemoryLimitguardRepositories()
        seedTenant(repositories, tenantOne)
        val service = catalogService(repositories)
        val ownerProfile = createIndividualProfile(service, tenantOne, "OWNER003")
        val assigneeProfile = createIndividualProfile(service, tenantOne, "ASSIGNEE003")
        val product = createLeafProduct(service, tenantOne)
        val fundingSource = createFundingSource(service, tenantOne, product.id)

        assertFailsWith<NotFoundException> {
            service.createMandate(
                CreateMandateCommand(
                    tenantId = tenantOne,
                    ownerPartyProfileId = ownerProfile.id,
                    assigneePartyProfileId = assigneeProfile.id,
                    name = "Family payment delegate",
                    fundingScope = LimitFundingScope.FUNDING_SOURCE,
                    fundingSourceId = fundingSource.id,
                )
            )
        }
    }

    @Test
    fun `create limit configuration returns scoped periodic result`() {
        val repositories = InMemoryLimitguardRepositories()
        seedTenant(repositories, tenantOne)
        val service = catalogService(repositories)
        val channel = service.registerChannel(
            RegisterChannelCommand(
                tenantId = tenantOne,
                code = "WEB",
                name = "Web banking",
            )
        )
        val operationType = service.registerOperationType(
            RegisterOperationTypeCommand(
                tenantId = tenantOne,
                code = "WIRE_TRANSFER",
                name = "Wire transfer",
                isLeaf = true,
            )
        )

        val result = service.createLimitConfiguration(
            CreateLimitConfigurationCommand(
                tenantId = tenantOne,
                code = "DAILY_WIRE_RETAIL",
                name = "Daily retail wire limit",
                limitKind = LimitKind.PERIODIC,
                period = LimitPeriod.DAILY,
                currency = "EUR",
                maxAssignableAmount = BigDecimal("1000.00"),
                defaultAmount = BigDecimal("250.00"),
                fundingScope = LimitConfigurationFundingScope.FUNDING_SOURCE_CATEGORY,
                fundingSourceCategory = FundingSourceCategory.TRANSACTIONAL,
                channelScope = LimitChannelScope.CHANNEL,
                channelId = channel.id,
                operationTypeId = operationType.id,
                metadata = mapOf("tier" to "retail"),
            )
        )

        assertEquals(LimitKind.PERIODIC, result.limitKind)
        assertEquals(LimitPeriod.DAILY, result.period)
        assertEquals("EUR", result.currency)
        assertEquals(LimitConfigurationFundingScope.FUNDING_SOURCE_CATEGORY, result.fundingScope)
        assertEquals(FundingSourceCategory.TRANSACTIONAL, result.fundingSourceCategory)
        assertEquals(null, result.fundingSourceProductId)
        assertEquals(LimitChannelScope.CHANNEL, result.channelScope)
        assertEquals(channel.id, result.channelId)
        assertEquals(operationType.id, result.operationTypeId)
        assertEquals(BigDecimal("1000.0000"), result.maxAssignableAmount)
        assertEquals(BigDecimal("250.0000"), result.defaultAmount)
        assertEquals(result.createdAt, result.updatedAt)
        assertEquals(mapOf("tier" to "retail"), result.metadata)
    }

    @Test
    fun `create limit configuration rejects missing channel`() {
        val repositories = InMemoryLimitguardRepositories()
        seedTenant(repositories, tenantOne)
        val service = catalogService(repositories)

        assertFailsWith<NotFoundException> {
            service.createLimitConfiguration(
                CreateLimitConfigurationCommand(
                    tenantId = tenantOne,
                    code = "DAILY_WIRE_RETAIL",
                    name = "Daily retail wire limit",
                    limitKind = LimitKind.PERIODIC,
                    period = LimitPeriod.DAILY,
                    currency = "EUR",
                    maxAssignableAmount = BigDecimal("1000.00"),
                    channelScope = LimitChannelScope.CHANNEL,
                    channelId = UUID(0L, 999L),
                )
            )
        }
    }

    @Test
    fun `create limit configuration rejects a child cap above its parent`() {
        val repositories = InMemoryLimitguardRepositories()
        seedTenant(repositories, tenantOne)
        val service = catalogService(repositories)
        val parent = service.createLimitConfiguration(
            CreateLimitConfigurationCommand(
                tenantId = tenantOne,
                code = "TRANSFER_CAP",
                name = "Transfer cap",
                limitKind = LimitKind.PER_TRANSACTION,
                currency = "EUR",
                maxAssignableAmount = BigDecimal("1000.00"),
            ),
        )

        assertFailsWith<ValidationException> {
            service.createLimitConfiguration(
                CreateLimitConfigurationCommand(
                    tenantId = tenantOne,
                    code = "PREMIUM_TRANSFER_CAP",
                    name = "Premium transfer cap",
                    limitKind = LimitKind.PER_TRANSACTION,
                    currency = "EUR",
                    maxAssignableAmount = BigDecimal("1000.01"),
                    parentConfigurationId = parent.id,
                ),
            )
        }
    }

    @Test
    fun `builds phase F1 catalog slice from empty store`() {
        val repositories = InMemoryLimitguardRepositories()
        val service = catalogService(repositories)

        val tenant = service.registerTenant(RegisterTenantCommand(name = "Demo Bank"))
        val channel = service.registerChannel(
            RegisterChannelCommand(
                tenantId = tenant.id,
                code = "ATM",
                name = "ATM",
            )
        )
        val operationType = service.registerOperationType(
            RegisterOperationTypeCommand(
                tenantId = tenant.id,
                code = "ATM_WITHDRAWAL",
                name = "ATM withdrawal",
                isLeaf = true,
            )
        )
        val product = createLeafProduct(service, tenant.id)
        val fundingSource = createFundingSource(service, tenant.id, product.id)
        val ownerProfile = createIndividualProfile(service, tenant.id, "OWNER004")
        val assigneeProfile = createIndividualProfile(service, tenant.id, "ASSIGNEE004")
        service.assignFundingSourceOwner(
            AssignFundingSourceOwnerCommand(
                tenantId = tenant.id,
                partyProfileId = ownerProfile.id,
                fundingSourceId = fundingSource.id,
            )
        )
        val mandate = service.createMandate(
            CreateMandateCommand(
                tenantId = tenant.id,
                ownerPartyProfileId = ownerProfile.id,
                assigneePartyProfileId = assigneeProfile.id,
                name = "ATM withdrawal mandate",
                fundingScope = LimitFundingScope.FUNDING_SOURCE,
                fundingSourceId = fundingSource.id,
            )
        )
        val configuration = service.createLimitConfiguration(
            CreateLimitConfigurationCommand(
                tenantId = tenant.id,
                code = "ATM_DAILY",
                name = "ATM daily limit",
                limitKind = LimitKind.PERIODIC,
                period = LimitPeriod.DAILY,
                currency = "EUR",
                maxAssignableAmount = BigDecimal("1000.00"),
                defaultAmount = BigDecimal("250.00"),
                fundingScope = LimitConfigurationFundingScope.FUNDING_SOURCE_PRODUCT,
                fundingSourceProductId = product.id,
                channelScope = LimitChannelScope.CHANNEL,
                channelId = channel.id,
                operationTypeId = operationType.id,
            )
        )

        assertEquals(tenant.id, configuration.tenantId)
        val persistedMandate = repositories.mandates.findById(TenantId.of(tenant.id), MandateId.of(mandate.id))
        assertEquals(mandate.id, persistedMandate?.id?.value)
        assertEquals(BigDecimal("1000.0000"), configuration.maxAssignableAmount)
    }

    private fun seedTenant(
        repositories: InMemoryLimitguardRepositories,
        tenantId: UUID,
        name: String = "Tenant ${tenantId.leastSignificantBits}",
    ) {
        repositories.tenants.insert(
            Tenant(
                id = TenantId.of(tenantId),
                name = TenantName.of(name),
                config = Metadata.EMPTY,
                createdAt = fixedNow,
            )
        )
    }

    private fun createIndividualProfile(
        service: DefaultTenantCatalogService,
        tenantId: UUID,
        legalIdentifierValue: String,
    ) = service.registerPartyProfile(
        individualCommand(
            tenantId = tenantId,
            legalIdentifier = codiceFiscale.copy(normalizedIdentifier = legalIdentifierValue),
        )
    )

    private fun createLeafProduct(
        service: DefaultTenantCatalogService,
        tenantId: UUID,
        code: String = "CC1000",
    ) = service.registerFundingSourceProduct(
        RegisterFundingSourceProductCommand(
            tenantId = tenantId,
            code = code,
            name = "Retail current account",
            category = FundingSourceCategory.TRANSACTIONAL,
            nodeType = ProductNodeType.PRODUCT,
            isLeaf = true,
        )
    )

    private fun createFundingSource(
        service: DefaultTenantCatalogService,
        tenantId: UUID,
        productId: UUID,
    ) = service.registerFundingSource(
        RegisterFundingSourceCommand(
            tenantId = tenantId,
            productId = productId,
        )
    )

    private fun individualCommand(
        tenantId: UUID,
        createPartyIfMissing: Boolean = true,
        legalIdentifier: PartyLegalIdentifierCommand = codiceFiscale,
    ): RegisterPartyProfileCommand =
        RegisterPartyProfileCommand(
            tenantId = tenantId,
            partyType = PartyType.INDIVIDUAL,
            createPartyIfMissing = createPartyIfMissing,
            firstName = "Mario",
            lastName = "Rossi",
            birthDate = LocalDate.parse("1980-01-01"),
            legalIdentifiers = listOf(legalIdentifier),
        )

    private fun catalogService(repositories: InMemoryLimitguardRepositories): DefaultTenantCatalogService =
        DefaultTenantCatalogService(
            tenantRepository = repositories.tenants,
            channelRepository = repositories.channels,
            operationTypeRepository = repositories.operationTypes,
            partyRepository = repositories.parties,
            individualRepository = repositories.individuals,
            legalEntityRepository = repositories.legalEntities,
            partyLegalIdentifierRepository = repositories.partyLegalIdentifiers,
            partyProfileRepository = repositories.partyProfiles,
            fundingSourceRepository = repositories.fundingSources,
            fundingSourceProductRepository = repositories.fundingSourceProducts,
            fundingSourceOwnerRepository = repositories.fundingSourceOwners,
            mandateRepository = repositories.mandates,
            limitConfigurationRepository = repositories.limitConfigurations,
            idGenerator = SequentialIdGenerator(),
            clockProvider = FixedClockProvider(Instant.parse("2026-06-21T00:00:00Z")),
            transactionRunner = DirectTransactionRunner(),
        )
}
