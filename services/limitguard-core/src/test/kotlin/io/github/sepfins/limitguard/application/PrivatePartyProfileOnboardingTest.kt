package io.github.sepfins.limitguard.application

import io.github.sepfins.limitguard.api.PartyType
import io.github.sepfins.limitguard.api.commands.EntityIdentifierCommand
import io.github.sepfins.limitguard.api.commands.PartyLegalIdentifierCommand
import io.github.sepfins.limitguard.api.commands.RegisterIdentifierSchemeCommand
import io.github.sepfins.limitguard.api.commands.RegisterPartyProfileCommand
import io.github.sepfins.limitguard.api.commands.RegisterTenantCommand
import io.github.sepfins.limitguard.api.queries.ResolveEntityIdentifierQuery
import io.github.sepfins.limitguard.api.errors.ValidationException
import io.github.sepfins.limitguard.api.errors.ConflictException
import io.github.sepfins.limitguard.support.FixedClockProvider
import io.github.sepfins.limitguard.support.createFacade
import io.github.sepfins.limitguard.support.persistence.InMemoryLimitguardRepositories
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class PrivatePartyProfileOnboardingTest {
    private val facade = InMemoryLimitguardRepositories().createFacade(FixedClockProvider(NOW), emptyList())

    @Test
    fun `ordinary onboarding returns only a tenant profile canonical handle and tenant identifiers`() {
        val tenant = facade.tenantCatalogService.registerTenant(RegisterTenantCommand("First bank")).id
        facade.tenantCatalogService.registerIdentifierScheme(RegisterIdentifierSchemeCommand(
            tenant, "PARTY_PROFILE", "CUSTOMER_NUMBER",
        ))
        val result = facade.tenantCatalogService.registerPartyProfile(RegisterPartyProfileCommand(
            tenantId = tenant, partyType = PartyType.INDIVIDUAL, firstName = "Mario", lastName = "Rossi",
            legalIdentifiers = listOf(LEGAL_ID), metadata = mapOf("segment" to "retail"),
            externalIdentifiers = listOf(EntityIdentifierCommand("CUSTOMER_NUMBER", "C001")),
        ))

        assertEquals(setOf("id", "tenantId", "partyId", "externalIdentifiers", "createdAt", "metadata"),
            result.javaClass.declaredFields.filterNot { it.isSynthetic }.map { it.name }.toSet())
        assertEquals(tenant, result.tenantId)
        assertEquals(mapOf("segment" to "retail"), result.metadata)
        assertEquals(NOW, result.createdAt)
        val external = result.externalIdentifiers.single()
        assertEquals(result.id, external.entityId)
        assertEquals(tenant, external.tenantId)
        assertEquals(external, facade.tenantCatalogQueryService.resolveEntityIdentifier(
            ResolveEntityIdentifierQuery(tenant, "PARTY_PROFILE", "CUSTOMER_NUMBER", "C001"),
        ))
    }

    @Test
    fun `ordinary onboarding cannot probe an existing party by omitting verified details`() {
        val first = facade.tenantCatalogService.registerTenant(RegisterTenantCommand("First bank")).id
        val second = facade.tenantCatalogService.registerTenant(RegisterTenantCommand("Second bank")).id
        facade.tenantCatalogService.registerPartyProfile(RegisterPartyProfileCommand(
            first, PartyType.INDIVIDUAL, firstName = "Mario", lastName = "Rossi", legalIdentifiers = listOf(LEGAL_ID),
        ))
        val missingDetails = RegisterPartyProfileCommand(second, PartyType.INDIVIDUAL, legalIdentifiers = listOf(LEGAL_ID))

        val matched = assertFailsWith<ValidationException> {
            facade.tenantCatalogService.registerPartyProfile(missingDetails)
        }
        val unmatched = assertFailsWith<ValidationException> {
            facade.tenantCatalogService.registerPartyProfile(missingDetails.copy(
                legalIdentifiers = listOf(LEGAL_ID.copy(normalizedIdentifier = "BNCLRA85C45F205Z")),
            ))
        }
        assertEquals(unmatched.message, matched.message)
        assertEquals(false, missingDetails.javaClass.declaredFields.any { it.name in setOf("partyId", "createPartyIfMissing") })
    }

    @Test
    fun `a new identifier alongside an existing match fails without extending shared identity`() {
        val first = facade.tenantCatalogService.registerTenant(RegisterTenantCommand("First bank")).id
        val second = facade.tenantCatalogService.registerTenant(RegisterTenantCommand("Second bank")).id
        val third = facade.tenantCatalogService.registerTenant(RegisterTenantCommand("Third bank")).id
        val command = RegisterPartyProfileCommand(
            first, PartyType.INDIVIDUAL, firstName = "Mario", lastName = "Rossi", legalIdentifiers = listOf(LEGAL_ID),
        )
        val original = facade.tenantCatalogService.registerPartyProfile(command)
        val extra = PartyLegalIdentifierCommand("IT", "PASSPORT", "YA1234567")

        val failure = assertFailsWith<ConflictException> {
            facade.tenantCatalogService.registerPartyProfile(command.copy(
                tenantId = second, legalIdentifiers = listOf(LEGAL_ID, extra),
            ))
        }
        assertEquals("party profile registration conflicts with existing data", failure.message)
        assertNull(failure.cause)
        val independent = facade.tenantCatalogService.registerPartyProfile(command.copy(
            tenantId = third, legalIdentifiers = listOf(extra),
        ))
        assertNotEquals(original.partyId, independent.partyId)
        val reused = facade.tenantCatalogService.registerPartyProfile(command.copy(tenantId = second))
        assertEquals(original.partyId, reused.partyId)
    }

    @Test
    fun `identical normalized legal identifiers do not turn initialization into a presence probe`() {
        val first = facade.tenantCatalogService.registerTenant(RegisterTenantCommand("First bank")).id
        val second = facade.tenantCatalogService.registerTenant(RegisterTenantCommand("Second bank")).id
        val command = RegisterPartyProfileCommand(
            first, PartyType.INDIVIDUAL, firstName = "Mario", lastName = "Rossi",
            legalIdentifiers = listOf(LEGAL_ID, LEGAL_ID.copy(
                jurisdictionCountry = " it ", identifierType = " codice_fiscale ", normalizedIdentifier = " rssmra80a01h501u ",
            )),
        )

        val initialized = facade.tenantCatalogService.registerPartyProfile(command)
        val reused = facade.tenantCatalogService.registerPartyProfile(command.copy(tenantId = second))

        assertEquals(initialized.partyId, reused.partyId)
    }

    @Test
    fun `canonical reuse retains separate tenant metadata and external identifier namespaces`() {
        val first = facade.tenantCatalogService.registerTenant(RegisterTenantCommand("First bank")).id
        val second = facade.tenantCatalogService.registerTenant(RegisterTenantCommand("Second bank")).id
        listOf(first, second).forEach { tenant ->
            facade.tenantCatalogService.registerIdentifierScheme(RegisterIdentifierSchemeCommand(
                tenant, "PARTY_PROFILE", "CUSTOMER_NUMBER",
            ))
        }
        val originalCommand = RegisterPartyProfileCommand(
            first, PartyType.INDIVIDUAL, firstName = "Mario", lastName = "Rossi",
            legalIdentifiers = listOf(LEGAL_ID, PartyLegalIdentifierCommand("IT", "PASSPORT", "YA1234567")),
            externalIdentifiers = listOf(EntityIdentifierCommand("CUSTOMER_NUMBER", "C001")),
            metadata = mapOf("segment" to "retail"),
        )
        val original = facade.tenantCatalogService.registerPartyProfile(originalCommand)
        val localMetadata = mutableMapOf<String, Any?>("segment" to mutableListOf("private"))
        val reused = facade.tenantCatalogService.registerPartyProfile(originalCommand.copy(
            tenantId = second, firstName = "New verified name", legalIdentifiers = listOf(LEGAL_ID), metadata = localMetadata,
        ))
        (localMetadata["segment"] as MutableList<*>).clear()

        assertEquals(original.partyId, reused.partyId)
        assertNotEquals(original.id, reused.id)
        assertEquals(mapOf("segment" to "retail"), original.metadata)
        assertEquals(mapOf("segment" to listOf("private")), reused.metadata)
        listOf(original, reused).forEach { result ->
            assertEquals(result.id, facade.tenantCatalogQueryService.resolveEntityIdentifier(
                ResolveEntityIdentifierQuery(result.tenantId, "PARTY_PROFILE", "CUSTOMER_NUMBER", "C001"),
            )?.entityId)
            assertEquals(result.tenantId, result.externalIdentifiers.single().tenantId)
        }
    }

    @Test
    fun `contradictory identities type mismatches and duplicate profiles have the same sanitized conflict`() {
        val first = facade.tenantCatalogService.registerTenant(RegisterTenantCommand("First bank")).id
        val second = facade.tenantCatalogService.registerTenant(RegisterTenantCommand("Second bank")).id
        val third = facade.tenantCatalogService.registerTenant(RegisterTenantCommand("Third bank")).id
        val firstCommand = RegisterPartyProfileCommand(
            first, PartyType.INDIVIDUAL, firstName = "Mario", lastName = "Rossi", legalIdentifiers = listOf(LEGAL_ID),
        )
        val firstProfile = facade.tenantCatalogService.registerPartyProfile(firstCommand)
        val otherId = PartyLegalIdentifierCommand("IT", "PASSPORT", "YA1234567")
        val otherProfile = facade.tenantCatalogService.registerPartyProfile(firstCommand.copy(
            tenantId = second, legalIdentifiers = listOf(otherId),
        ))
        val invalidCommands = listOf(
            firstCommand.copy(tenantId = third, legalIdentifiers = listOf(LEGAL_ID, otherId)),
            firstCommand.copy(tenantId = third, partyType = PartyType.LEGAL_ENTITY, legalName = "Host verified company"),
            firstCommand,
        )
        invalidCommands.forEach { invalid ->
            val failure = assertFailsWith<ConflictException> { facade.tenantCatalogService.registerPartyProfile(invalid) }
            assertEquals("party profile registration conflicts with existing data", failure.message)
            assertNull(failure.cause)
        }
        val afterFailure = facade.tenantCatalogService.registerPartyProfile(firstCommand.copy(tenantId = third))
        assertEquals(firstProfile.partyId, afterFailure.partyId)
        assertNotEquals(otherProfile.partyId, afterFailure.partyId)
    }

    companion object {
        private val NOW = Instant.parse("2026-10-11T10:00:00Z")
        private val LEGAL_ID = PartyLegalIdentifierCommand("IT", "CODICE_FISCALE", "RSSMRA80A01H501U")
    }
}
