package io.github.sepfins.limitguard.infrastructure.jooq

import io.github.sepfins.limitguard.api.commands.RegisterFundingSourceCommand
import io.github.sepfins.limitguard.api.commands.RegisterFundingSourceProductCommand
import io.github.sepfins.limitguard.api.commands.UpdateFundingSourceCommand
import io.github.sepfins.limitguard.api.errors.InfrastructureException
import io.github.sepfins.limitguard.api.errors.NotFoundException
import io.github.sepfins.limitguard.support.FixedClockProvider
import io.github.sepfins.limitguard.support.FundingProductScenario
import io.github.sepfins.limitguard.support.persistence.ObservedDataSource
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import java.sql.SQLException
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

@Tag("database")
@Testcontainers
@Timeout(30)
class FundingProductLeafPersistenceTest {
    @Test
    fun `PostgreSQL facade rejects foreign parent product and source references without partial writes`() {
        val s = fixture().scenario
        val other = FundingProductScenario(s.facade)
        val family = other.product("FAMILY")
        val leaf = other.product("ACCOUNT", family.id, leaf = true)
        val source = other.source(leaf.id, "account-1")

        assertFailsWith<NotFoundException> { s.product("CHILD", family.id, leaf = true) }
        assertFailsWith<NotFoundException> { s.source(leaf.id, "account-1") }
        assertFailsWith<NotFoundException> {
            s.facade.tenantCatalogService.updateFundingSource(UpdateFundingSourceCommand(
                s.tenant, source.id, "moved-account", emptyMap(),
            ))
        }
        assertNull(s.findProduct("CHILD"))
        assertNull(s.findSource("account-1"))
        assertNull(s.findSource("moved-account"))
        assertEquals(source, other.findSource("account-1"))
        assertEquals(leaf, other.findProduct("ACCOUNT"))
    }

    @Test
    fun `failure after source or child insertion rolls back and leaves business keys reusable`() {
        for (sourceAdmission in listOf(true, false)) {
            val fixture = fixture()
            val s = fixture.scenario
            val parent = s.product("PARENT", leaf = sourceAdmission)
            val table = if (sourceAdmission) "funding_source" else "funding_source_product"
            val failing = JooqLimitguardFactory(ObservedDataSource(fixture.source, after = { sql ->
                if (sql.startsWith("insert into \"$table\"")) {
                    throw SQLException("Injected failure after registration", "XX000")
                }
            })).create(clockProvider = FixedClockProvider(NOW))

            assertFailsWith<InfrastructureException> {
                if (sourceAdmission) {
                    failing.tenantCatalogService.registerFundingSource(RegisterFundingSourceCommand(
                        s.tenant, parent.id, externalReference = "account-1",
                    ))
                } else {
                    failing.tenantCatalogService.registerFundingSourceProduct(RegisterFundingSourceProductCommand(
                        s.tenant, "CHILD", "Child", parent.category, parent.nodeType,
                        parentId = parent.id, isLeaf = true,
                    ))
                }
            }
            assertEquals(parent, s.findProduct("PARENT"))
            assertNull(s.findSource("account-1"))
            assertNull(s.findProduct("CHILD"))
            if (sourceAdmission) {
                assertEquals(parent.id, s.source(parent.id, "account-1").productId)
            } else {
                assertEquals(parent.id, s.product("CHILD", parent.id, leaf = true).parentId)
            }
        }
    }

    private fun fixture(): Fixture {
        val schema = "funding_${UUID.randomUUID().toString().replace("-", "")}"
        Flyway.configure().dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .schemas(schema).defaultSchema(schema).locations("classpath:db/limitguard/migration")
            .load().migrate()
        val source = PGSimpleDataSource().apply {
            setURL(postgres.jdbcUrl)
            user = postgres.username
            password = postgres.password
            currentSchema = schema
        }
        val facade = JooqLimitguardFactory(source).create(clockProvider = FixedClockProvider(NOW))
        return Fixture(source, FundingProductScenario(facade))
    }

    private data class Fixture(val source: DataSource, val scenario: FundingProductScenario)

    companion object {
        private val NOW = Instant.parse("2026-10-10T10:00:00Z")

        @Container
        @JvmField
        val postgres = PostgreSQLContainer("postgres:18-alpine")
    }
}
