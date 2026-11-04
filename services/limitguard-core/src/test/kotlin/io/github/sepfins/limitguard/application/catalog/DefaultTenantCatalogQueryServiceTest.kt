package io.github.sepfins.limitguard.application.catalog

import io.github.sepfins.limitguard.api.commands.RegisterTenantCommand
import io.github.sepfins.limitguard.api.errors.NotFoundException
import io.github.sepfins.limitguard.api.errors.ValidationException
import io.github.sepfins.limitguard.api.queries.ListFundingSourceProductsQuery
import io.github.sepfins.limitguard.api.queries.ListTenantsQuery
import io.github.sepfins.limitguard.api.queries.GetTenantQuery
import io.github.sepfins.limitguard.support.FixedClockProvider
import io.github.sepfins.limitguard.support.PostgresFacade
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DefaultTenantCatalogQueryServiceTest {
    private val facade = PostgresFacade.create(FixedClockProvider(NOW))
    private val service = facade.tenantCatalogQueryService

    @Test
    fun `gets one tenant by id and rejects a missing tenant`() {
        val tenant = facade.tenantCatalogService.registerTenant(RegisterTenantCommand("Known Bank", ZoneOffset.UTC))
        assertEquals("Known Bank", service.getTenant(GetTenantQuery(tenant.id)).name)

        assertFailsWith<NotFoundException> {
            service.getTenant(GetTenantQuery(UUID.randomUUID()))
        }
        assertFailsWith<NotFoundException> {
            service.listFundingSourceProducts(ListFundingSourceProductsQuery(tenantId = UUID.randomUUID()))
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

    companion object {
        private val NOW: Instant = Instant.parse("2026-08-09T00:00:00Z")
    }
}
