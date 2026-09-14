package io.github.sepfins.limitguard.application.catalog

import io.github.sepfins.limitguard.api.errors.NotFoundException
import io.github.sepfins.limitguard.api.errors.ValidationException
import io.github.sepfins.limitguard.api.queries.ListFundingSourceProductsQuery
import io.github.sepfins.limitguard.api.queries.ListTenantsQuery
import io.github.sepfins.limitguard.api.queries.GetTenantQuery
import io.github.sepfins.limitguard.domain.common.FundingSourceProductId
import io.github.sepfins.limitguard.domain.common.TenantId
import io.github.sepfins.limitguard.domain.funding.FundingSourceCategory
import io.github.sepfins.limitguard.domain.funding.FundingSourceProduct
import io.github.sepfins.limitguard.domain.funding.FundingSourceProductCode
import io.github.sepfins.limitguard.domain.funding.FundingSourceProductName
import io.github.sepfins.limitguard.domain.funding.ProductNodeType
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
    fun `lists tenants with stable paging and literal case insensitive search`() {
        insertTenant(3, "Zulu Bank")
        insertTenant(1, "Alpha Bank")
        insertTenant(2, "Percent% Bank")

        val firstPage = service.listTenants(ListTenantsQuery(page = 0, size = 2))
        assertEquals(listOf("Alpha Bank", "Percent% Bank"), firstPage.items.map { it.name })
        assertEquals(3, firstPage.totalElements)
        assertEquals(2, firstPage.totalPages)

        val secondPage = service.listTenants(ListTenantsQuery(page = 1, size = 2))
        assertEquals(listOf("Zulu Bank"), secondPage.items.map { it.name })

        val percentSearch = service.listTenants(ListTenantsQuery(search = "% bAnK"))
        assertEquals(listOf("Percent% Bank"), percentSearch.items.map { it.name })
    }

    @Test
    fun `gets one tenant by id and rejects a missing tenant`() {
        val tenant = insertTenant(5, "Known Bank")
        assertEquals("Known Bank", service.getTenant(GetTenantQuery(tenant.id.value)).name)

        assertFailsWith<NotFoundException> {
            service.getTenant(GetTenantQuery(id(404)))
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

    @Test
    fun `lists products only inside the requested tenant and applies filters`() {
        val tenant = insertTenant(10, "Primary")
        val otherTenant = insertTenant(20, "Other")
        insertProduct(tenant, 11, "CARD", "Payment Card", active = true)
        insertProduct(tenant, 12, "SAVINGS", "Savings Account", active = false)
        insertProduct(otherTenant, 21, "CARD", "Other Card", active = true)

        val all = service.listFundingSourceProducts(
            ListFundingSourceProductsQuery(tenantId = tenant.id.value),
        )
        assertEquals(listOf("CARD", "SAVINGS"), all.items.map { it.code })
        assertEquals(2, all.totalElements)

        val inactive = service.listFundingSourceProducts(
            ListFundingSourceProductsQuery(
                tenantId = tenant.id.value,
                search = "account",
                active = false,
            ),
        )
        assertEquals(listOf("SAVINGS"), inactive.items.map { it.code })

        assertFailsWith<NotFoundException> {
            service.listFundingSourceProducts(
                ListFundingSourceProductsQuery(tenantId = id(999)),
            )
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

    private fun insertProduct(
        tenant: Tenant,
        number: Long,
        code: String,
        name: String,
        active: Boolean,
    ): FundingSourceProduct = repositories.fundingSourceProducts.insert(
        tenant.id,
        FundingSourceProduct(
            id = FundingSourceProductId.of(id(number)),
            tenantId = tenant.id,
            parentId = null,
            accountCategory = FundingSourceCategory.TRANSACTIONAL,
            nodeType = ProductNodeType.PRODUCT,
            code = FundingSourceProductCode.of(code),
            name = FundingSourceProductName.of(name),
            isLeaf = true,
            isActive = active,
            createdAt = NOW,
        ),
    )

    private fun id(value: Long): UUID = UUID(0L, value)

    companion object {
        private val NOW: Instant = Instant.parse("2026-08-09T00:00:00Z")
    }
}
