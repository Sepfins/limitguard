package io.github.sepfins.limitguard.application.catalog

import io.github.sepfins.limitguard.api.FundingSourceCategory
import io.github.sepfins.limitguard.api.EntityIdentifierTypes
import io.github.sepfins.limitguard.api.LimitChangePolicyDecision
import io.github.sepfins.limitguard.api.LimitChangePolicyPhase
import io.github.sepfins.limitguard.api.PartyType
import io.github.sepfins.limitguard.api.ProductNodeType
import io.github.sepfins.limitguard.api.commands.AssignEntityIdentifierCommand
import io.github.sepfins.limitguard.api.commands.CreateLimitChangePolicyCommand
import io.github.sepfins.limitguard.api.commands.EntityIdentifierCommand
import io.github.sepfins.limitguard.api.commands.PartyLegalIdentifierCommand
import io.github.sepfins.limitguard.api.commands.RegisterFundingSourceCommand
import io.github.sepfins.limitguard.api.commands.RegisterFundingSourceProductCommand
import io.github.sepfins.limitguard.api.commands.RegisterIdentifierSchemeCommand
import io.github.sepfins.limitguard.api.commands.RegisterPartyProfileCommand
import io.github.sepfins.limitguard.api.commands.RegisterTenantCommand
import io.github.sepfins.limitguard.api.errors.NotFoundException
import io.github.sepfins.limitguard.api.errors.ValidationException
import io.github.sepfins.limitguard.api.queries.ResolveEntityIdentifierQuery
import io.github.sepfins.limitguard.api.TenantCatalogQueryService
import io.github.sepfins.limitguard.api.TenantCatalogService
import io.github.sepfins.limitguard.support.PostgresFacade
import io.github.sepfins.limitguard.support.FixedClockProvider
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class TenantCatalogCoverageTest {
    private val now = Instant.parse("2026-08-09T10:00:00Z")

    @Test
    fun `bank defined NDG and IBAN identifiers resolve to their LimitGuard entities`() {
        val fixture = fixture()
        val tenant = fixture.service.registerTenant(RegisterTenantCommand(name = "Access Key Bank", timeZone = ZoneOffset.UTC))
        val otherTenant = fixture.service.registerTenant(RegisterTenantCommand(name = "Other Bank", timeZone = ZoneOffset.UTC))
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
        assertFailsWith<ValidationException> {
            fixture.service.assignEntityIdentifier(
                AssignEntityIdentifierCommand(tenant.id, EntityIdentifierTypes.PARTY_PROFILE, profile.id, "NDG", "1234567X")
            )
        }
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
        fixture.service.registerIdentifierScheme(
            RegisterIdentifierSchemeCommand(otherTenant.id, EntityIdentifierTypes.FUNDING_SOURCE, "IBAN")
        )
        assertFailsWith<NotFoundException> {
            fixture.service.assignEntityIdentifier(
                AssignEntityIdentifierCommand(
                    tenantId = otherTenant.id,
                    entityType = EntityIdentifierTypes.FUNDING_SOURCE,
                    entityId = source.id,
                    schemeName = "IBAN",
                    identifier = "IT60X0542811101000000123456",
                )
            )
        }
        assertFailsWith<ValidationException> {
            fixture.service.registerIdentifierScheme(RegisterIdentifierSchemeCommand(tenant.id, "CHANNEL", "CODE"))
        }
        assertFailsWith<ValidationException> {
            fixture.service.assignEntityIdentifier(
                AssignEntityIdentifierCommand(tenant.id, "CHANNEL", source.id, "IBAN", "IT60X0542811101000000123456")
            )
        }
    }

    @Test
    fun `catalog rejects an unsupported policy expression and keeps the code free for a corrected policy`() {
        val fixture = fixture()
        val tenant = fixture.service.registerTenant(RegisterTenantCommand(name = "Policy Bank", timeZone = ZoneOffset.UTC))

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
        assertEquals("INVALID_POLICY", fixture.service.createLimitChangePolicy(CreateLimitChangePolicyCommand(
            tenantId = tenant.id, code = "INVALID_POLICY", name = "Corrected policy", phase = LimitChangePolicyPhase.AUTHORIZATION,
        )).code)
    }

    @Test
    fun `catalog rejects unsupported policy phases and decisions before persistence`() {
        val fixture = fixture()
        val tenant = fixture.service.registerTenant(RegisterTenantCommand(name = "Unsupported Policy Bank", timeZone = ZoneOffset.UTC))

        LimitChangePolicyPhase.values()
            .filter { it != LimitChangePolicyPhase.AUTHORIZATION }
            .forEach { phase ->
                val code = "UNSUPPORTED_PHASE_${phase.name}"
                assertFailsWith<ValidationException> {
                    fixture.service.createLimitChangePolicy(
                        CreateLimitChangePolicyCommand(
                            tenantId = tenant.id,
                            code = code,
                            name = code,
                            phase = phase,
                            decision = LimitChangePolicyDecision.DENY,
                        )
                    )
                }
            }

        LimitChangePolicyDecision.values()
            .filter {
                it !in setOf(
                    LimitChangePolicyDecision.ALLOW,
                    LimitChangePolicyDecision.DENY,
                    LimitChangePolicyDecision.REQUIRE_APPROVAL,
                    LimitChangePolicyDecision.NO_OP,
                )
            }
            .forEach { decision ->
                val code = "UNSUPPORTED_DECISION_${decision.name}"
                assertFailsWith<ValidationException> {
                    fixture.service.createLimitChangePolicy(
                        CreateLimitChangePolicyCommand(
                            tenantId = tenant.id,
                            code = code,
                            name = code,
                            phase = LimitChangePolicyPhase.AUTHORIZATION,
                            decision = decision,
                        )
                    )
                }
            }
    }

    private fun individualProfileCommand(
        tenantId: UUID,
        legalIdentifier: String,
        externalIdentifiers: List<EntityIdentifierCommand> = emptyList(),
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
        )

    private fun fixture(): Fixture {
        val facade = PostgresFacade.create(FixedClockProvider(now))
        return Fixture(facade.tenantCatalogService, facade.tenantCatalogQueryService)
    }

    private data class Fixture(
        val service: TenantCatalogService,
        val queryService: TenantCatalogQueryService,
    )
}
