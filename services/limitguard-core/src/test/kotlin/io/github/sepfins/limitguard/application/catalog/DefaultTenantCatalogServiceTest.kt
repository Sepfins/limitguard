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
import io.github.sepfins.limitguard.api.commands.RegisterFundingSourceCommand
import io.github.sepfins.limitguard.api.commands.RegisterFundingSourceProductCommand
import io.github.sepfins.limitguard.api.commands.RegisterOperationTypeCommand
import io.github.sepfins.limitguard.api.commands.RegisterPartyProfileCommand
import io.github.sepfins.limitguard.api.errors.NotFoundException
import io.github.sepfins.limitguard.api.errors.ValidationException
import io.github.sepfins.limitguard.domain.common.PartyId
import io.github.sepfins.limitguard.domain.common.TenantId
import io.github.sepfins.limitguard.domain.party.FirstName
import io.github.sepfins.limitguard.domain.party.Individual
import io.github.sepfins.limitguard.domain.party.LastName
import io.github.sepfins.limitguard.domain.party.Party
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
    private val codiceFiscale = PartyLegalIdentifierCommand(
        jurisdictionCountry = "IT",
        identifierType = "CODICE_FISCALE",
        normalizedIdentifier = "RSSMRA80A01H501U",
    )

    @Test
    fun `register party profile creates party when legal identifier is new`() {
        val repositories = InMemoryLimitguardRepositories()
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
    fun `register operation type requires tenant context even for global scope`() {
        val repositories = InMemoryLimitguardRepositories()
        val service = catalogService(repositories)

        assertFailsWith<ValidationException> {
            service.registerOperationType(
                RegisterOperationTypeCommand(
                    tenantId = tenantOne,
                    code = "CARD_PAYMENT",
                    name = "Card payment",
                    isGlobal = true,
                )
            )
        }
    }

    @Test
    fun `register funding source catalog returns metadata and ownership`() {
        val repositories = InMemoryLimitguardRepositories()
        val service = catalogService(repositories)
        val productMetadata = mapOf("catalog" to "retail")
        val fundingSourceMetadata = mapOf("ibanCountry" to "IT")

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
                partyProfileId = UUID(0L, 201L),
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
    fun `create mandate returns scoped result`() {
        val repositories = InMemoryLimitguardRepositories()
        val service = catalogService(repositories)
        val ownerProfileId = UUID(0L, 301L)
        val assigneeProfileId = UUID(0L, 302L)
        val fundingSourceId = UUID(0L, 303L)
        val startsAt = Instant.parse("2026-07-01T00:00:00Z")

        val result = service.createMandate(
            CreateMandateCommand(
                tenantId = tenantOne,
                ownerPartyProfileId = ownerProfileId,
                assigneePartyProfileId = assigneeProfileId,
                name = "Family payment delegate",
                fundingScope = LimitFundingScope.FUNDING_SOURCE,
                fundingSourceId = fundingSourceId,
                startsAt = startsAt,
                metadata = mapOf("reason" to "family"),
            )
        )

        assertEquals(LimitFundingScope.FUNDING_SOURCE, result.fundingScope)
        assertEquals(fundingSourceId, result.fundingSourceId)
        assertEquals(null, result.fundingSourceGroupId)
        assertFalse(result.isActive)
        assertEquals(startsAt, result.startsAt)
        assertEquals(result.createdAt, result.updatedAt)
        assertEquals(mapOf("reason" to "family"), result.metadata)
    }

    @Test
    fun `create limit configuration returns scoped periodic result`() {
        val repositories = InMemoryLimitguardRepositories()
        val service = catalogService(repositories)
        val channelId = UUID(0L, 401L)
        val operationTypeId = UUID(0L, 402L)

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
                channelId = channelId,
                operationTypeId = operationTypeId,
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
        assertEquals(channelId, result.channelId)
        assertEquals(operationTypeId, result.operationTypeId)
        assertEquals(BigDecimal("1000.0000"), result.maxAssignableAmount)
        assertEquals(BigDecimal("250.0000"), result.defaultAmount)
        assertEquals(result.createdAt, result.updatedAt)
        assertEquals(mapOf("tier" to "retail"), result.metadata)
    }

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
