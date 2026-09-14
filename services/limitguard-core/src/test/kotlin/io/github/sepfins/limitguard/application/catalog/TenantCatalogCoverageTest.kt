package io.github.sepfins.limitguard.application.catalog

import io.github.sepfins.limitguard.api.FundingSourceCategory
import io.github.sepfins.limitguard.api.EntityIdentifierTypes
import io.github.sepfins.limitguard.api.LimitChangePolicyDecision
import io.github.sepfins.limitguard.api.LimitChangePolicyPhase
import io.github.sepfins.limitguard.api.LimitFundingScope
import io.github.sepfins.limitguard.api.PartyProfileDelegationType
import io.github.sepfins.limitguard.api.PartyType
import io.github.sepfins.limitguard.api.ProductNodeType
import io.github.sepfins.limitguard.api.commands.AddChannelGroupMemberCommand
import io.github.sepfins.limitguard.api.commands.AddFundingSourceGroupMemberCommand
import io.github.sepfins.limitguard.api.commands.AssignEntityIdentifierCommand
import io.github.sepfins.limitguard.api.commands.AssignFundingSourceOwnerCommand
import io.github.sepfins.limitguard.api.commands.CreateChannelGroupCommand
import io.github.sepfins.limitguard.api.commands.CreateFundingSourceGroupCommand
import io.github.sepfins.limitguard.api.commands.CreateLimitChangePolicyCommand
import io.github.sepfins.limitguard.api.commands.CreateMandateCommand
import io.github.sepfins.limitguard.api.commands.CreatePartyProfileDelegationCommand
import io.github.sepfins.limitguard.api.commands.EntityIdentifierCommand
import io.github.sepfins.limitguard.api.commands.PartyLegalIdentifierCommand
import io.github.sepfins.limitguard.api.commands.RegisterBankWorkerCommand
import io.github.sepfins.limitguard.api.commands.RegisterChannelCommand
import io.github.sepfins.limitguard.api.commands.RegisterFundingSourceCommand
import io.github.sepfins.limitguard.api.commands.RegisterFundingSourceProductCommand
import io.github.sepfins.limitguard.api.commands.RegisterIdentifierSchemeCommand
import io.github.sepfins.limitguard.api.commands.RegisterPartyProfileCommand
import io.github.sepfins.limitguard.api.commands.RegisterTenantCommand
import io.github.sepfins.limitguard.api.errors.NotFoundException
import io.github.sepfins.limitguard.api.errors.ValidationException
import io.github.sepfins.limitguard.api.queries.ResolveEntityIdentifierQuery
import io.github.sepfins.limitguard.domain.change.LimitChangePolicyCode
import io.github.sepfins.limitguard.domain.common.ChannelId
import io.github.sepfins.limitguard.domain.common.FundingSourceGroupId
import io.github.sepfins.limitguard.domain.common.TenantId
import io.github.sepfins.limitguard.domain.identifier.EntityType
import io.github.sepfins.limitguard.infrastructure.policy.jsonlogic.JsonLogicPolicyEngine
import io.github.sepfins.limitguard.support.DirectTransactionRunner
import io.github.sepfins.limitguard.support.FixedClockProvider
import io.github.sepfins.limitguard.support.SequentialIdGenerator
import io.github.sepfins.limitguard.support.persistence.InMemoryLimitguardRepositories
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TenantCatalogCoverageTest {
    private val now = Instant.parse("2026-08-09T10:00:00Z")

    @Test
    fun `party profile external identifiers are validated persisted returned and assignable`() {
        val fixture = fixture()
        val tenant = fixture.service.registerTenant(RegisterTenantCommand(name = "Identifier Bank"))
        fixture.service.registerIdentifierScheme(
            RegisterIdentifierSchemeCommand(
                tenantId = tenant.id,
                entityType = EntityIdentifierTypes.PARTY_PROFILE,
                schemeName = "CUSTOMER_NUMBER",
                formatRegex = "CUST-[0-9]{6}",
                maxLength = 11,
                isPrimary = true,
            )
        )

        val profile = fixture.service.registerPartyProfile(
            individualProfileCommand(
                tenantId = tenant.id,
                legalIdentifier = "RSSMRA80A01H501U",
                externalIdentifiers = listOf(
                    EntityIdentifierCommand("CUSTOMER_NUMBER", "CUST-000001")
                ),
                metadata = mapOf("segment" to "retail"),
            )
        )

        assertEquals("CUST-000001", profile.externalIdentifiers.single().identifier)
        assertEquals(mapOf("segment" to "retail"), profile.metadata)
        assertEquals(
            1,
            fixture.repositories.entityIdentifiers.findByEntity(
                TenantId.of(tenant.id),
                EntityType.PARTY_PROFILE,
                profile.id,
            ).size,
        )

        val secondIdentifier = fixture.service.assignEntityIdentifier(
            AssignEntityIdentifierCommand(
                tenantId = tenant.id,
                entityType = EntityIdentifierTypes.PARTY_PROFILE,
                entityId = profile.id,
                schemeName = "CUSTOMER_NUMBER",
                identifier = "CUST-000002",
            )
        )
        assertEquals("CUST-000002", secondIdentifier.identifier)

        assertFailsWith<ValidationException> {
            fixture.service.assignEntityIdentifier(
                AssignEntityIdentifierCommand(
                    tenantId = tenant.id,
                    entityType = EntityIdentifierTypes.PARTY_PROFILE,
                    entityId = profile.id,
                    schemeName = "CUSTOMER_NUMBER",
                    identifier = "invalid",
                )
            )
        }
    }

    @Test
    fun `bank defined NDG and IBAN identifiers resolve to their LimitGuard entities`() {
        val fixture = fixture()
        val tenant = fixture.service.registerTenant(RegisterTenantCommand(name = "Access Key Bank"))
        val otherTenant = fixture.service.registerTenant(RegisterTenantCommand(name = "Other Bank"))
        fixture.service.registerIdentifierScheme(
            RegisterIdentifierSchemeCommand(
                tenantId = tenant.id,
                entityType = EntityIdentifierTypes.PARTY_PROFILE,
                schemeName = "NDG",
                formatRegex = "[0-9]{8}",
                isPrimary = true,
            )
        )
        fixture.service.registerIdentifierScheme(
            RegisterIdentifierSchemeCommand(
                tenantId = tenant.id,
                entityType = EntityIdentifierTypes.FUNDING_SOURCE,
                schemeName = "IBAN",
                formatRegex = "[A-Z]{2}[0-9A-Z]{13,32}",
                maxLength = 34,
                isPrimary = true,
            )
        )

        val profile = fixture.service.registerPartyProfile(
            individualProfileCommand(
                tenantId = tenant.id,
                legalIdentifier = "RSSMRA80A01H501U",
                externalIdentifiers = listOf(EntityIdentifierCommand("NDG", "12345678")),
            )
        )
        val product = fixture.service.registerFundingSourceProduct(
            RegisterFundingSourceProductCommand(
                tenantId = tenant.id,
                code = "CURRENT_ACCOUNT",
                name = "Current account",
                category = FundingSourceCategory.TRANSACTIONAL,
                nodeType = ProductNodeType.PRODUCT,
                isLeaf = true,
            )
        )
        val source = fixture.service.registerFundingSource(
            RegisterFundingSourceCommand(
                tenantId = tenant.id,
                productId = product.id,
                externalReference = "ACCOUNT-1",
            )
        )
        fixture.service.assignEntityIdentifier(
            AssignEntityIdentifierCommand(
                tenantId = tenant.id,
                entityType = EntityIdentifierTypes.FUNDING_SOURCE,
                entityId = source.id,
                schemeName = "IBAN",
                identifier = "IT60X0542811101000000123456",
            )
        )

        assertEquals(
            profile.id,
            fixture.queryService.resolveEntityIdentifier(
                ResolveEntityIdentifierQuery(
                    tenantId = tenant.id,
                    entityType = EntityIdentifierTypes.PARTY_PROFILE,
                    schemeName = "NDG",
                    identifier = "12345678",
                )
            )?.entityId,
        )
        assertEquals(
            source.id,
            fixture.queryService.resolveEntityIdentifier(
                ResolveEntityIdentifierQuery(
                    tenantId = tenant.id,
                    entityType = EntityIdentifierTypes.FUNDING_SOURCE,
                    schemeName = "IBAN",
                    identifier = "IT60X0542811101000000123456",
                )
            )?.entityId,
        )
        assertNull(
            fixture.queryService.resolveEntityIdentifier(
                ResolveEntityIdentifierQuery(
                    tenantId = otherTenant.id,
                    entityType = EntityIdentifierTypes.FUNDING_SOURCE,
                    schemeName = "IBAN",
                    identifier = "IT60X0542811101000000123456",
                )
            )
        )
        assertFailsWith<NotFoundException> {
            fixture.service.assignEntityIdentifier(
                AssignEntityIdentifierCommand(
                    tenantId = tenant.id,
                    entityType = EntityIdentifierTypes.FUNDING_SOURCE,
                    entityId = UUID.randomUUID(),
                    schemeName = "IBAN",
                    identifier = "IT60X0542811101000000000000",
                )
            )
        }
    }

    @Test
    fun `catalog creates staff delegation groups memberships and a group scoped mandate`() {
        val fixture = fixture()
        val tenant = fixture.service.registerTenant(RegisterTenantCommand(name = "Full Catalog Bank"))
        val channel = fixture.service.registerChannel(
            RegisterChannelCommand(tenant.id, "MOBILE", "Mobile banking")
        )
        val channelGroup = fixture.service.createChannelGroup(
            CreateChannelGroupCommand(tenant.id, "DIGITAL", "Digital channels")
        )
        val channelMember = fixture.service.addChannelGroupMember(
            AddChannelGroupMemberCommand(tenant.id, channelGroup.id, channel.id)
        )
        assertEquals(channel.id, channelMember.channelId)

        val owner = fixture.service.registerPartyProfile(
            individualProfileCommand(tenant.id, "RSSMRA80A01H501U")
        )
        val delegate = fixture.service.registerPartyProfile(
            individualProfileCommand(tenant.id, "BNCLRA85C45F205Z")
        )
        val worker = fixture.service.registerBankWorker(
            RegisterBankWorkerCommand(
                tenantId = tenant.id,
                externalReference = "EMP-100",
                displayName = "Giulia Bianchi",
                branchCode = "rm-01",
            )
        )
        assertEquals("RM-01", worker.branchCode)

        val delegation = fixture.service.createPartyProfileDelegation(
            CreatePartyProfileDelegationCommand(
                tenantId = tenant.id,
                ownerPartyProfileId = owner.id,
                delegatePartyProfileId = delegate.id,
                delegationType = PartyProfileDelegationType.AUTHORIZED_USER,
            )
        )

        val product = fixture.service.registerFundingSourceProduct(
            RegisterFundingSourceProductCommand(
                tenantId = tenant.id,
                code = "CURRENT_ACCOUNT",
                name = "Current account",
                category = FundingSourceCategory.TRANSACTIONAL,
                nodeType = ProductNodeType.PRODUCT,
                isLeaf = true,
            )
        )
        val source = fixture.service.registerFundingSource(
            RegisterFundingSourceCommand(
                tenantId = tenant.id,
                productId = product.id,
                externalReference = "IT60X0542811101000000123456",
            )
        )
        fixture.service.assignFundingSourceOwner(
            AssignFundingSourceOwnerCommand(tenant.id, owner.id, source.id)
        )
        val group = fixture.service.createFundingSourceGroup(
            CreateFundingSourceGroupCommand(
                tenantId = tenant.id,
                ownerPartyProfileId = owner.id,
                code = "DAILY_BANKING",
                name = "Daily banking",
            )
        )
        val fundingMember = fixture.service.addFundingSourceGroupMember(
            AddFundingSourceGroupMemberCommand(tenant.id, group.id, source.id)
        )
        assertEquals(owner.id, fundingMember.ownerPartyProfileId)

        val mandate = fixture.service.createMandate(
            CreateMandateCommand(
                tenantId = tenant.id,
                ownerPartyProfileId = owner.id,
                assigneePartyProfileId = delegate.id,
                authorityDelegationId = delegation.id,
                name = "Daily banking mandate",
                fundingScope = LimitFundingScope.FUNDING_SOURCE_GROUP,
                fundingSourceGroupId = group.id,
            )
        )

        assertEquals(group.id, mandate.fundingSourceGroupId)
        assertEquals(delegation.id, mandate.authorityDelegationId)
        assertEquals(
            setOf(channelGroup.id),
            fixture.repositories.channelGroupMembers.findGroupIdsContaining(
                TenantId.of(tenant.id),
                ChannelId.of(channel.id),
            ).mapTo(mutableSetOf()) { it.value },
        )
        assertTrue(
            fixture.repositories.fundingSourceGroupMembers.findByGroup(
                TenantId.of(tenant.id),
                FundingSourceGroupId.of(group.id),
            ).isNotEmpty()
        )
    }

    @Test
    fun `catalog validates and persists change policies`() {
        val fixture = fixture()
        val tenant = fixture.service.registerTenant(RegisterTenantCommand(name = "Policy Bank"))

        val policy = fixture.service.createLimitChangePolicy(
            CreateLimitChangePolicyCommand(
                tenantId = tenant.id,
                code = "ALLOW_DIGITAL",
                name = "Allow digital requests",
                phase = LimitChangePolicyPhase.AUTHORIZATION,
                decision = LimitChangePolicyDecision.ALLOW,
                conditionExpression = mapOf("==" to listOf(1, 1)),
                metadata = mapOf("owner" to "limits-team"),
            )
        )

        assertEquals("ALLOW_DIGITAL", policy.code)
        assertEquals(now, policy.createdAt)
        assertNotNull(
            fixture.repositories.limitChangePolicies.findByCode(
                TenantId.of(tenant.id),
                LimitChangePolicyCode.of("ALLOW_DIGITAL"),
            )
        )

        // The built-in orderings are refused too: they compare as doubles, facts included.
        listOf("unsupported", ">", ">=", "<", "<=").forEach { operation ->
            assertFailsWith<ValidationException> {
                fixture.service.createLimitChangePolicy(
                    CreateLimitChangePolicyCommand(
                        tenantId = tenant.id,
                        code = "INVALID_POLICY",
                        name = "Invalid policy",
                        phase = LimitChangePolicyPhase.AUTHORIZATION,
                        conditionExpression = mapOf(operation to listOf(mapOf("var" to "facts.currentExposure"), "100")),
                    )
                )
            }
        }
    }

    private fun individualProfileCommand(
        tenantId: UUID,
        legalIdentifier: String,
        externalIdentifiers: List<EntityIdentifierCommand> = emptyList(),
        metadata: Map<String, Any?> = emptyMap(),
    ): RegisterPartyProfileCommand =
        RegisterPartyProfileCommand(
            tenantId = tenantId,
            partyType = PartyType.INDIVIDUAL,
            firstName = "Mario",
            lastName = "Rossi",
            birthDate = LocalDate.parse("1980-01-01"),
            legalIdentifiers = listOf(
                PartyLegalIdentifierCommand(
                    jurisdictionCountry = "IT",
                    identifierType = "CODICE_FISCALE",
                    normalizedIdentifier = legalIdentifier,
                )
            ),
            externalIdentifiers = externalIdentifiers,
            metadata = metadata,
        )

    private fun fixture(): Fixture {
        val repositories = InMemoryLimitguardRepositories()
        return Fixture(
            repositories = repositories,
            service = DefaultTenantCatalogService(
                tenantRepository = repositories.tenants,
                identifierSchemeRepository = repositories.identifierSchemes,
                entityIdentifierRepository = repositories.entityIdentifiers,
                channelRepository = repositories.channels,
                channelGroupRepository = repositories.channelGroups,
                channelGroupMemberRepository = repositories.channelGroupMembers,
                operationTypeRepository = repositories.operationTypes,
                partyRepository = repositories.parties,
                individualRepository = repositories.individuals,
                legalEntityRepository = repositories.legalEntities,
                partyLegalIdentifierRepository = repositories.partyLegalIdentifiers,
                partyProfileRepository = repositories.partyProfiles,
                partyProfileDelegationRepository = repositories.partyProfileDelegations,
                bankWorkerRepository = repositories.bankWorkers,
                fundingSourceRepository = repositories.fundingSources,
                fundingSourceProductRepository = repositories.fundingSourceProducts,
                fundingSourceOwnerRepository = repositories.fundingSourceOwners,
                fundingSourceGroupRepository = repositories.fundingSourceGroups,
                fundingSourceGroupMemberRepository = repositories.fundingSourceGroupMembers,
                mandateRepository = repositories.mandates,
                limitConfigurationRepository = repositories.limitConfigurations,
                limitChangePolicyRepository = repositories.limitChangePolicies,
                policyEngines = listOf(JsonLogicPolicyEngine()),
                idGenerator = SequentialIdGenerator(),
                clockProvider = FixedClockProvider(now),
                transactionRunner = DirectTransactionRunner(),
            ),
            queryService = DefaultTenantCatalogQueryService(
                tenantRepository = repositories.tenants,
                identifierSchemeRepository = repositories.identifierSchemes,
                entityIdentifierRepository = repositories.entityIdentifiers,
                fundingSourceProductRepository = repositories.fundingSourceProducts,
                transactionRunner = DirectTransactionRunner(),
            ),
        )
    }

    private data class Fixture(
        val repositories: InMemoryLimitguardRepositories,
        val service: DefaultTenantCatalogService,
        val queryService: DefaultTenantCatalogQueryService,
    )
}
