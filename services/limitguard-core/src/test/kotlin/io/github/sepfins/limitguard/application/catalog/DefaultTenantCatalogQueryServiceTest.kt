package io.github.sepfins.limitguard.application.catalog

import io.github.sepfins.limitguard.api.errors.NotFoundException
import io.github.sepfins.limitguard.api.errors.ValidationException
import io.github.sepfins.limitguard.api.queries.ListFundingSourceProductsQuery
import io.github.sepfins.limitguard.api.queries.ListTenantsQuery
import io.github.sepfins.limitguard.api.queries.GetTenantQuery
import io.github.sepfins.limitguard.domain.common.TenantId
import io.github.sepfins.limitguard.domain.tenant.Tenant
import io.github.sepfins.limitguard.domain.tenant.TenantName
import io.github.sepfins.limitguard.support.DirectTransactionRunner
import io.github.sepfins.limitguard.support.persistence.InMemoryLimitguardRepositories
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DefaultTenantCatalogQueryServiceTest {
    private val repositories = InMemoryLimitguardRepositories()
    private val service = DefaultTenantCatalogQueryService(
        tenantRepository = repositories.tenants,
        identifierSchemeRepository = repositories.identifierSchemes,
        entityIdentifierRepository = repositories.entityIdentifiers,
        fundingSourceProductRepository = repositories.fundingSourceProducts,
        transactionRunner = DirectTransactionRunner(),
    )

    @Test
    fun `gets one tenant by id and rejects a missing tenant`() {
        val tenant = insertTenant(5, "Known Bank")
        assertEquals("Known Bank", service.getTenant(GetTenantQuery(tenant.id.value)).name)

        assertFailsWith<NotFoundException> {
            service.getTenant(GetTenantQuery(id(404)))
        }
        assertFailsWith<NotFoundException> {
            service.listFundingSourceProducts(ListFundingSourceProductsQuery(tenantId = id(404)))
        }
    }

    @Test
    fun `validates page and search bounds`() {
        assertFailsWith<ValidationException> {
            service.listTenants(ListTenantsQuery(page = -1))
        }
        assertFailsWith<ValidationException> {
            service.listTenants(ListTenantsQuery(size = 101))
        }
        assertFailsWith<ValidationException> {
            service.listTenants(ListTenantsQuery(search = "x".repeat(101)))
        }
    }

    private fun insertTenant(number: Long, name: String): Tenant =
        repositories.tenants.insert(
            Tenant(
                id = TenantId.of(id(number)),
                name = TenantName.of(name),
                createdAt = NOW,
            ),
        )

    private fun id(value: Long): UUID = UUID(0L, value)

    companion object {
        private val NOW: Instant = Instant.parse("2026-08-09T00:00:00Z")
    }
}
