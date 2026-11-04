package io.github.sepfins.limitguard.infrastructure.jooq

import io.github.sepfins.limitguard.api.Limitguard
import io.github.sepfins.limitguard.api.PartyType
import io.github.sepfins.limitguard.api.PartyProfileDelegationType
import io.github.sepfins.limitguard.api.AuthorizationDecision
import io.github.sepfins.limitguard.api.FundingSourceOwnerRole
import io.github.sepfins.limitguard.api.commands.AssignFundingSourceOwnerCommand
import io.github.sepfins.limitguard.api.commands.CreatePartyProfileDelegationCommand
import io.github.sepfins.limitguard.api.commands.CreateMandateCommand
import io.github.sepfins.limitguard.api.commands.EntityIdentifierCommand
import io.github.sepfins.limitguard.api.commands.PartyLegalIdentifierCommand
import io.github.sepfins.limitguard.api.commands.RegisterIdentifierSchemeCommand
import io.github.sepfins.limitguard.api.commands.RegisterPartyProfileCommand
import io.github.sepfins.limitguard.api.commands.RegisterTenantCommand
import io.github.sepfins.limitguard.api.errors.InfrastructureException
import io.github.sepfins.limitguard.api.errors.ConflictException
import io.github.sepfins.limitguard.api.errors.NotFoundException
import io.github.sepfins.limitguard.api.queries.ResolveEntityIdentifierQuery
import io.github.sepfins.limitguard.api.queries.GetLimitRuleQuery
import io.github.sepfins.limitguard.api.results.PartyProfileResult
import io.github.sepfins.limitguard.support.FixedClockProvider
import io.github.sepfins.limitguard.support.OperationSubtreeScenario
import io.github.sepfins.limitguard.support.persistence.ObservedDataSource
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import java.sql.SQLException
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertNotEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@Testcontainers
@Timeout(30)
class PrivatePartyProfilePersistenceTest {
    @Test
    fun `infrastructure failure hides SQL evidence and rolls back canonical initialization`() {
        val f = fixture()
        val tenant = f.tenant("First bank")
        val failing = facade(ObservedDataSource(f.source, after = { sql ->
            if (sql.startsWith("insert into \"party_legal_identifier\"")) {
                throw SQLException("Protected SQL evidence: ${LEGAL_ID.normalizedIdentifier}", "XX000")
            }
        }))

        val failure = assertFailsWith<InfrastructureException> {
            failing.tenantCatalogService.registerPartyProfile(command(tenant))
        }

        assertEquals("party profile registration failed", failure.message)
        assertNull(failure.cause)
        assertNull(f.resolve(tenant, "C001"))
        assertEquals(listOf(listOf(0L, 0L, 0L, 0L)), counts(f.source))
        val registered = f.facade.tenantCatalogService.registerPartyProfile(command(tenant))
        assertEquals(registered.id, f.resolve(tenant, "C001")?.entityId)
        assertEquals(listOf(listOf(1L, 1L, 1L, 1L)), counts(f.source))
    }

    @Test
    fun `reuse of individuals and legal entities changes no canonical details or legal identifier evidence`() {
        val f = fixture()
        val first = f.tenant("First bank")
        val second = f.tenant("Second bank")
        for (type in PartyType.entries) {
            val legalId = if (type == PartyType.INDIVIDUAL) LEGAL_ID else PartyLegalIdentifierCommand("IT", "PARTITA_IVA", "12345678901")
            val originalCommand = command(first, type.name).copy(
                partyType = type, birthDate = LocalDate.parse("1980-01-01"), legalName = "Original verified company", legalForm = "SRL",
                legalIdentifiers = listOf(legalId.copy(metadata = mapOf("verifiedBy" to "first bank")),
                    PartyLegalIdentifierCommand("IT", "REGISTRY_REFERENCE", "REF_${type.name}")),
            )
            val original = f.facade.tenantCatalogService.registerPartyProfile(originalCommand)
            val before = canonicalState(f.source)
            val privateFacade = facade(ObservedDataSource(f.source, before = { sql ->
                if (sql.startsWith("select") && (sql.contains("from \"individual\"") || sql.contains("from \"legal_entity\""))) {
                    throw SQLException("Ordinary reuse must not read protected details", "XX000")
                }
            }))
            val reused = privateFacade.tenantCatalogService.registerPartyProfile(originalCommand.copy(
                tenantId = second, firstName = "Different", lastName = "Verified", birthDate = LocalDate.parse("1990-01-01"),
                legalName = "Different verified company", legalForm = "SPA", metadata = mapOf("segment" to "private"),
                legalIdentifiers = listOf(legalId.copy(metadata = mapOf("verifiedBy" to "second bank"))),
            ))

            assertEquals(original.partyId, reused.partyId)
            assertNotEquals(original.id, reused.id)
            assertEquals(before, canonicalState(f.source))
            assertEquals(mapOf("segment" to "retail"), original.metadata)
            assertEquals(mapOf("segment" to "private"), reused.metadata)
            assertEquals(original.id, f.resolve(first, type.name)?.entityId)
            assertEquals(reused.id, f.resolve(second, type.name)?.entityId)
        }
    }

    @Test
    fun `failed external identifier attachment leaves neither new nor reused partial registrations`() {
        val f = fixture()
        val first = f.tenant("First bank")
        val second = f.tenant("Second bank")
        val original = f.facade.tenantCatalogService.registerPartyProfile(command(first))
        val localId = LEGAL_ID.copy(normalizedIdentifier = "BNCLRA85C45F205Z")
        val existing = f.facade.tenantCatalogService.registerPartyProfile(command(second).copy(legalIdentifiers = listOf(localId)))
        val before = canonicalState(f.source)
        for (legalId in listOf(LEGAL_ID, LEGAL_ID.copy(normalizedIdentifier = "NEW_VERIFIED_ID"))) {
            val invalid = command(second).copy(
                legalIdentifiers = listOf(legalId), externalIdentifiers = listOf(
                    EntityIdentifierCommand("CUSTOMER_NUMBER", "FREE"), EntityIdentifierCommand("CUSTOMER_NUMBER", "C001"),
                ),
            )

            sanitizedConflict { f.facade.tenantCatalogService.registerPartyProfile(invalid) }

            assertEquals(before, canonicalState(f.source))
            assertEquals(listOf(listOf(2L, 2L, 2L, 2L)), counts(f.source))
            assertNull(f.resolve(second, "FREE"))
            assertEquals(existing.id, f.resolve(second, "C001")?.entityId)
        }
        val retry = f.facade.tenantCatalogService.registerPartyProfile(command(second, "FREE"))
        assertEquals(original.partyId, retry.partyId)
        assertEquals(retry.id, f.resolve(second, "FREE")?.entityId)
        assertEquals(before, canonicalState(f.source))
    }

    @Test
    fun `concurrent initialization commits one canonical party and a sanitized loser can retry across tenants`() {
        val f = fixture()
        val commands = listOf(command(f.tenant("First bank")), command(f.tenant("Second bank")))
        val results = race(f, commands, "party")
        val winner = results.single { it.isSuccess }.getOrThrow()
        val loserCommand = commands[results.indexOfFirst { it.isFailure }]
        assertEquals(listOf(listOf(1L, 1L, 1L, 1L)), counts(f.source))
        assertNull(f.resolve(loserCommand.tenantId, "C001"))
        val beforeRetry = canonicalState(f.source)

        val retry = f.facade.tenantCatalogService.registerPartyProfile(loserCommand)

        assertEquals(winner.partyId, retry.partyId)
        assertEquals(beforeRetry, canonicalState(f.source))
        assertEquals(listOf(listOf(1L, 1L, 2L, 2L)), counts(f.source))
        assertEquals(winner.id, f.resolve(winner.tenantId, "C001")?.entityId)
        assertEquals(retry.id, f.resolve(loserCommand.tenantId, "C001")?.entityId)
    }

    @Test
    fun `concurrent reuse in one tenant preserves one profile and no losing external identifier`() {
        val f = fixture()
        val original = f.facade.tenantCatalogService.registerPartyProfile(command(f.tenant("First bank")))
        val second = f.tenant("Second bank")
        val commands = listOf(command(second, "FIRST"), command(second, "SECOND"))
        val results = race(f, commands, "party_profile")
        val winner = results.single { it.isSuccess }.getOrThrow()
        val losingCommand = commands[results.indexOfFirst { it.isFailure }]

        assertEquals(original.partyId, winner.partyId)
        assertEquals(listOf(listOf(1L, 1L, 2L, 2L)), counts(f.source))
        assertEquals(winner.id, f.resolve(second, winner.externalIdentifiers.single().identifier)?.entityId)
        assertNull(f.resolve(second, losingCommand.externalIdentifiers.single().identifier))
        sanitizedConflict { f.facade.tenantCatalogService.registerPartyProfile(losingCommand) }
        assertEquals(listOf(listOf(1L, 1L, 2L, 2L)), counts(f.source))
    }

    @Test
    fun `unrelated schema registries do not share canonical matching`() {
        val first = fixture()
        val second = fixture()
        val firstProfile = first.facade.tenantCatalogService.registerPartyProfile(command(first.tenant("Bank")))
        val secondProfile = second.facade.tenantCatalogService.registerPartyProfile(command(second.tenant("Bank")))

        assertNotEquals(firstProfile.partyId, secondProfile.partyId)
        assertEquals(listOf(listOf(1L, 1L, 1L, 1L)), counts(first.source))
        assertEquals(listOf(listOf(1L, 1L, 1L, 1L)), counts(second.source))
    }

    @Test
    fun `shared canonical owners and delegates retain tenant isolated authority rules and usage`() {
        val f = fixture()
        val first = OperationSubtreeScenario(f.facade, NOW)
        val second = OperationSubtreeScenario(f.facade, NOW)
        val catalog = f.facade.tenantCatalogService
        val ownerCommand = RegisterPartyProfileCommand(
            first.tenant, PartyType.INDIVIDUAL, firstName = "Mario", lastName = "Rossi", legalIdentifiers = listOf(LEGAL_ID),
        )
        val delegateCommand = ownerCommand.copy(firstName = "Luisa", legalIdentifiers = listOf(
            PartyLegalIdentifierCommand("IT", "PASSPORT", "YA1234567"),
        ))
        val firstOwner = catalog.registerPartyProfile(ownerCommand)
        val secondOwner = catalog.registerPartyProfile(ownerCommand.copy(tenantId = second.tenant))
        val firstDelegate = catalog.registerPartyProfile(delegateCommand)
        val secondDelegate = catalog.registerPartyProfile(delegateCommand.copy(tenantId = second.tenant))
        assertEquals(firstOwner.partyId, secondOwner.partyId)
        assertEquals(firstDelegate.partyId, secondDelegate.partyId)
        listOf(first to firstOwner, second to secondOwner).forEach { (scenario, owner) ->
            catalog.assignFundingSourceOwner(AssignFundingSourceOwnerCommand(
                scenario.tenant, owner.id, scenario.source, FundingSourceOwnerRole.JOINT_OWNER,
            ))
        }
        val delegation = catalog.createPartyProfileDelegation(CreatePartyProfileDelegationCommand(
            first.tenant, firstOwner.id, firstDelegate.id, PartyProfileDelegationType.LEGAL_REPRESENTATIVE,
        ))
        catalog.createMandate(CreateMandateCommand(
            first.tenant, firstOwner.id, firstDelegate.id, "Local grant", authorityDelegationId = delegation.id,
        ))
        val firstOperation = first.operation("TRANSFER")
        val secondOperation = second.operation("TRANSFER")
        val firstRule = first.grant(firstOperation, "100", ruleOwner = firstOwner.id)
        val secondRule = second.grant(secondOperation, "500", ruleOwner = secondOwner.id)

        assertEquals(AuthorizationDecision.ALLOWED, first.authorize(firstOperation, "60", firstDelegate.id).decision)
        assertEquals(AuthorizationDecision.DENIED, second.authorize(secondOperation, "60", secondDelegate.id).decision)
        assertEquals(AuthorizationDecision.ALLOWED, second.authorize(secondOperation, "400", secondOwner.id).decision)
        assertEquals(0, BigDecimal("40").compareTo(first.usage(firstRule, firstDelegate.id).remainingAmount))
        assertEquals(0, BigDecimal("100").compareTo(second.usage(secondRule, secondOwner.id).remainingAmount))
        assertFailsWith<NotFoundException> {
            second.authorize(secondOperation, "10", firstOwner.id)
        }
        assertFailsWith<NotFoundException> {
            f.facade.limitInquiryService.getLimitRule(GetLimitRuleQuery(second.tenant, firstRule))
        }
        assertFailsWith<NotFoundException> {
            catalog.createMandate(CreateMandateCommand(
                second.tenant, secondOwner.id, secondDelegate.id, "Local grant", authorityDelegationId = delegation.id,
            ))
        }
        catalog.createMandate(CreateMandateCommand(second.tenant, secondOwner.id, secondDelegate.id, "Local grant"))
        assertEquals(AuthorizationDecision.ALLOWED, second.authorize(secondOperation, "70", secondDelegate.id).decision)
        assertEquals(0, BigDecimal("430").compareTo(second.usage(secondRule, secondDelegate.id).remainingAmount))
        assertEquals(0, BigDecimal("100").compareTo(second.usage(secondRule, secondOwner.id).remainingAmount))
        assertEquals(0, BigDecimal("40").compareTo(first.usage(firstRule, firstDelegate.id).remainingAmount))
        assertEquals(AuthorizationDecision.DENIED, first.authorize(firstOperation, "41", firstDelegate.id).decision)
    }

    private fun race(f: Fixture, commands: List<RegisterPartyProfileCommand>, table: String): List<Result<PartyProfileResult>> {
        val ready = CountDownLatch(2)
        val release = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val futures = commands.map { command ->
                val contender = facade(ObservedDataSource(f.source, before = { sql ->
                    if (sql.startsWith("insert into \"$table\"")) {
                        ready.countDown()
                        assertTrue(release.await(10, TimeUnit.SECONDS), "Registration barrier timed out")
                    }
                }))
                executor.submit<Result<PartyProfileResult>> {
                    runCatching { contender.tenantCatalogService.registerPartyProfile(command) }
                }
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS), "Both registrations must read the same pre-insert state")
            release.countDown()
            val results = futures.map { it.get(10, TimeUnit.SECONDS) }
            assertEquals(1, results.count { it.isSuccess })
            val failure = assertIs<ConflictException>(results.single { it.isFailure }.exceptionOrNull())
            assertEquals("party profile registration conflicts with existing data", failure.message)
            assertNull(failure.cause)
            return results
        } finally {
            release.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    private fun sanitizedConflict(action: () -> Unit) {
        val failure = assertFailsWith<ConflictException> { action() }
        assertEquals("party profile registration conflicts with existing data", failure.message)
        assertNull(failure.cause)
    }

    private fun fixture(): Fixture {
        val schema = "private_profile_${UUID.randomUUID().toString().replace("-", "")}"
        Flyway.configure().dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .schemas(schema).defaultSchema(schema).locations("classpath:db/limitguard/migration").load().migrate()
        val source = PGSimpleDataSource().apply {
            setURL(postgres.jdbcUrl)
            user = postgres.username
            password = postgres.password
            currentSchema = schema
        }
        return Fixture(source, facade(source))
    }

    private fun facade(source: DataSource): Limitguard =
        JooqLimitguardFactory(source).create(clockProvider = FixedClockProvider(NOW))

    private fun command(tenant: UUID, number: String = "C001") = RegisterPartyProfileCommand(
        tenant, PartyType.INDIVIDUAL, firstName = "Mario", lastName = "Rossi",
        legalIdentifiers = listOf(LEGAL_ID), metadata = mapOf("segment" to "retail"),
        externalIdentifiers = listOf(EntityIdentifierCommand("CUSTOMER_NUMBER", number)),
    )

    // Raw SQL, because these checks cover stored values the public APIs do not disclose.
    private fun rows(source: DataSource, sql: String): List<List<Any?>> = source.connection.use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { result ->
                buildList {
                    while (result.next()) add((1..result.metaData.columnCount).map { result.getObject(it) })
                }
            }
        }
    }

    private fun counts(source: DataSource) = rows(source, """
        select (select count(*) from party), (select count(*) from party_legal_identifier),
               (select count(*) from party_profile), (select count(*) from entity_identifier)
    """.trimIndent())

    private fun canonicalState(source: DataSource) = listOf("party", "individual", "legal_entity", "party_legal_identifier")
        .associateWith { rows(source, "select * from $it order by 1") }

    private data class Fixture(val source: DataSource, val facade: Limitguard) {
        fun tenant(name: String): UUID {
            val id = facade.tenantCatalogService.registerTenant(RegisterTenantCommand(name, ZoneOffset.UTC)).id
            facade.tenantCatalogService.registerIdentifierScheme(RegisterIdentifierSchemeCommand(
                id, "PARTY_PROFILE", "CUSTOMER_NUMBER",
            ))
            return id
        }

        fun resolve(tenant: UUID, number: String) = facade.tenantCatalogQueryService.resolveEntityIdentifier(
            ResolveEntityIdentifierQuery(tenant, "PARTY_PROFILE", "CUSTOMER_NUMBER", number),
        )
    }

    companion object {
        private val NOW = Instant.parse("2026-10-11T10:00:00Z")
        private val LEGAL_ID = PartyLegalIdentifierCommand("IT", "CODICE_FISCALE", "RSSMRA80A01H501U")

        @Container
        @JvmField
        val postgres = PostgreSQLContainer("postgres:18-alpine")
    }
}
