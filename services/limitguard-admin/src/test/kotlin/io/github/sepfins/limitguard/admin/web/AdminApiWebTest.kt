package io.github.sepfins.limitguard.admin.web

import io.github.sepfins.limitguard.admin.application.AdminCatalogApplicationService
import io.github.sepfins.limitguard.admin.security.AdminAuthorizer
import io.github.sepfins.limitguard.admin.security.AdminIdentityResolver
import io.github.sepfins.limitguard.admin.security.AdminSecurityErrorWriter
import io.github.sepfins.limitguard.admin.security.AdminSecurityProperties
import io.github.sepfins.limitguard.admin.security.SecurityConfiguration
import io.github.sepfins.limitguard.admin.support.platformAdmin
import io.github.sepfins.limitguard.admin.support.tenantAdmin
import io.github.sepfins.limitguard.admin.support.viewer
import io.github.sepfins.limitguard.admin.web.error.AdminExceptionHandler
import io.github.sepfins.limitguard.api.FundingSourceCategory
import io.github.sepfins.limitguard.api.Limitguard
import io.github.sepfins.limitguard.api.NoMatchingRuleBehavior
import io.github.sepfins.limitguard.api.ProductNodeType
import io.github.sepfins.limitguard.api.TenantCatalogQueryService
import io.github.sepfins.limitguard.api.TenantCatalogService
import io.github.sepfins.limitguard.api.commands.RegisterFundingSourceProductCommand
import io.github.sepfins.limitguard.api.commands.RegisterTenantCommand
import io.github.sepfins.limitguard.api.errors.ConflictException
import io.github.sepfins.limitguard.api.errors.InfrastructureException
import io.github.sepfins.limitguard.api.errors.NotFoundException
import io.github.sepfins.limitguard.api.queries.ListFundingSourceProductsQuery
import io.github.sepfins.limitguard.api.queries.ListTenantsQuery
import io.github.sepfins.limitguard.api.results.FundingSourceProductResult
import io.github.sepfins.limitguard.api.results.PageResult
import io.github.sepfins.limitguard.api.results.TenantResult
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.time.Instant
import java.util.UUID

@WebMvcTest(
    controllers = [
        AdminSessionController::class,
        TenantAdminController::class,
        FundingSourceProductAdminController::class,
    ],
)
@Import(
    AdminCatalogApplicationService::class,
    AdminAuthorizer::class,
    AdminIdentityResolver::class,
    AdminSecurityErrorWriter::class,
    SecurityConfiguration::class,
    CorrelationIdFilter::class,
    AdminExceptionHandler::class,
    AdminApiWebTest.TestBeans::class,
)
class AdminApiWebTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val limitguard: Limitguard,
) {
    private lateinit var catalogService: TenantCatalogService
    private lateinit var queryService: TenantCatalogQueryService

    @BeforeEach
    fun setUp() {
        catalogService = mock(TenantCatalogService::class.java)
        queryService = mock(TenantCatalogQueryService::class.java)
        `when`(limitguard.tenantCatalogService).thenReturn(catalogService)
        `when`(limitguard.tenantCatalogQueryService).thenReturn(queryService)
    }

    @Test
    fun `unauthenticated API requests use the common error envelope`() {
        mockMvc.get("/limitguard/admin/api/v1/me") {
            header(CorrelationIdFilter.HEADER_NAME, "request-401")
        }.andExpect {
            status { isUnauthorized() }
            header { string(CorrelationIdFilter.HEADER_NAME, "request-401") }
            jsonPath("$.code") { value("UNAUTHORIZED") }
            jsonPath("$.correlationId") { value("request-401") }
            jsonPath("$.fieldViolations") { isArray() }
        }
    }

    @Test
    fun `me exposes mapped roles and tenant memberships`() {
        val tenantId = UUID.randomUUID()
        mockMvc.get("/limitguard/admin/api/v1/me") {
            with(tenantAdmin(tenantId))
        }.andExpect {
            status { isOk() }
            jsonPath("$.subject") { value("admin-user") }
            jsonPath("$.roles[0]") { value("TENANT_ADMIN") }
            jsonPath("$.tenantIds[0]") { value(tenantId.toString()) }
            jsonPath("$.logoutUrl") { value("/limitguard/admin/logout") }
            jsonPath("$.csrfParameterName") { value("_csrf") }
            jsonPath("$.csrfToken") { isNotEmpty() }
        }
    }

    @Test
    fun `platform admin receives paginated tenant DTOs`() {
        val tenant = tenantResult(name = "Retail Italy")
        `when`(queryService.listTenants(ListTenantsQuery(search = "retail", page = 0, size = 25)))
            .thenReturn(PageResult(listOf(tenant), 0, 25, 1, 1))

        mockMvc.get("/limitguard/admin/api/v1/tenants") {
            param("search", "retail")
            with(platformAdmin())
        }.andExpect {
            status { isOk() }
            jsonPath("$.items[0].id") { value(tenant.id.toString()) }
            jsonPath("$.items[0].name") { value("Retail Italy") }
            jsonPath("$.items[0].config") { doesNotExist() }
            jsonPath("$.totalElements") { value(1) }
        }
    }

    @Test
    fun `tenant creation requires CSRF and returns 201 with location`() {
        val tenant = tenantResult()
        val expectedCommand = RegisterTenantCommand(
            name = "Retail Italy",
            config = emptyMap(),
            noMatchingRuleBehavior = NoMatchingRuleBehavior.DENY,
        )
        `when`(catalogService.registerTenant(expectedCommand))
            .thenReturn(tenant)

        mockMvc.post("/limitguard/admin/api/v1/tenants") {
            with(platformAdmin())
            with(csrf())
            contentType = MediaType.APPLICATION_JSON
            content = """{"name":"Retail Italy","noMatchingRuleBehavior":"DENY"}"""
        }.andExpect {
            status { isCreated() }
            header { string("Location", "/limitguard/admin/api/v1/tenants/${tenant.id}") }
            jsonPath("$.name") { value("Retail Italy") }
        }

        verify(catalogService).registerTenant(expectedCommand)

        mockMvc.post("/limitguard/admin/api/v1/tenants") {
            with(platformAdmin())
            contentType = MediaType.APPLICATION_JSON
            content = """{"name":"Retail Italy"}"""
        }.andExpect {
            status { isForbidden() }
            jsonPath("$.code") { value("FORBIDDEN") }
            jsonPath("$.correlationId") { isNotEmpty() }
        }
    }

    @Test
    fun `viewer cannot mutate`() {
        mockMvc.post("/limitguard/admin/api/v1/tenants") {
            with(viewer(UUID.randomUUID()))
            with(csrf())
            contentType = MediaType.APPLICATION_JSON
            content = """{"name":"Forbidden tenant"}"""
        }.andExpect {
            status { isForbidden() }
            jsonPath("$.code") { value("FORBIDDEN") }
        }
    }

    @Test
    fun `tenant claim is enforced for product reads and writes`() {
        val authorizedTenant = UUID.randomUUID()
        val unauthorizedTenant = UUID.randomUUID()
        `when`(
            queryService.listFundingSourceProducts(
                ListFundingSourceProductsQuery(authorizedTenant, null, true, 0, 25),
            ),
        ).thenReturn(PageResult(emptyList(), 0, 25, 0, 0))

        mockMvc.get(
            "/limitguard/admin/api/v1/tenants/$authorizedTenant/funding-source-products",
        ) {
            param("active", "true")
            with(tenantAdmin(authorizedTenant))
        }.andExpect {
            status { isOk() }
            jsonPath("$.items") { isEmpty() }
        }

        mockMvc.get(
            "/limitguard/admin/api/v1/tenants/$unauthorizedTenant/funding-source-products",
        ) {
            with(tenantAdmin(authorizedTenant))
        }.andExpect {
            status { isForbidden() }
            jsonPath("$.code") { value("FORBIDDEN") }
        }

        mockMvc.post(
            "/limitguard/admin/api/v1/tenants/$unauthorizedTenant/funding-source-products",
        ) {
            with(tenantAdmin(authorizedTenant))
            with(csrf())
            contentType = MediaType.APPLICATION_JSON
            content = productRequest
        }.andExpect {
            status { isForbidden() }
        }
    }

    @Test
    fun `product creation maps the DTO and omits metadata`() {
        val tenantId = UUID.randomUUID()
        val product = productResult(tenantId)
        val expectedCommand = RegisterFundingSourceProductCommand(
            tenantId = tenantId,
            code = "CARDS",
            name = "Cards",
            category = FundingSourceCategory.CREDIT,
            nodeType = ProductNodeType.FAMILY,
            parentId = null,
            isLeaf = false,
            isActive = true,
            metadata = emptyMap(),
        )
        `when`(catalogService.registerFundingSourceProduct(expectedCommand))
            .thenReturn(product)

        mockMvc.post("/limitguard/admin/api/v1/tenants/$tenantId/funding-source-products") {
            with(tenantAdmin(tenantId))
            with(csrf())
            contentType = MediaType.APPLICATION_JSON
            content = productRequest
        }.andExpect {
            status { isCreated() }
            header {
                string(
                    "Location",
                    "/limitguard/admin/api/v1/tenants/$tenantId/funding-source-products/${product.id}",
                )
            }
            jsonPath("$.code") { value("CARDS") }
            jsonPath("$.metadata") { doesNotExist() }
        }

        verify(catalogService).registerFundingSourceProduct(expectedCommand)
    }

    @Test
    fun `core failures map to stable statuses and infrastructure details are sanitized`() {
        `when`(queryService.listTenants(ListTenantsQuery()))
            .thenThrow(NotFoundException("missing reference"))
            .thenThrow(ConflictException("duplicate tenant"))
            .thenThrow(InfrastructureException("jdbc:postgresql://secret-host/password"))

        mockMvc.get("/limitguard/admin/api/v1/tenants") {
            with(platformAdmin())
        }.andExpect {
            status { isNotFound() }
            jsonPath("$.code") { value("NOT_FOUND") }
        }
        mockMvc.get("/limitguard/admin/api/v1/tenants") {
            with(platformAdmin())
        }.andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("CONFLICT") }
        }
        mockMvc.get("/limitguard/admin/api/v1/tenants") {
            with(platformAdmin())
        }.andExpect {
            status { isServiceUnavailable() }
            jsonPath("$.code") { value("INFRASTRUCTURE_FAILURE") }
            content { string(containsString("temporarily unavailable")) }
            content { string(not(containsString("secret-host"))) }
        }
    }

    @Test
    fun `invalid request body returns field violations`() {
        mockMvc.post("/limitguard/admin/api/v1/tenants") {
            with(platformAdmin())
            with(csrf())
            contentType = MediaType.APPLICATION_JSON
            content = """{"name":""}"""
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("VALIDATION_FAILED") }
            jsonPath("$.fieldViolations[0].field") { value("name") }
        }
    }

    private fun tenantResult(
        id: UUID = UUID.randomUUID(),
        name: String = "Retail Italy",
    ) = TenantResult(
        id = id,
        name = name,
        config = mapOf("hidden" to true),
        noMatchingRuleBehavior = NoMatchingRuleBehavior.DENY,
        createdAt = Instant.parse("2026-08-09T10:00:00Z"),
    )

    private fun productResult(tenantId: UUID) = FundingSourceProductResult(
        id = UUID.randomUUID(),
        tenantId = tenantId,
        parentId = null,
        category = FundingSourceCategory.CREDIT,
        nodeType = ProductNodeType.FAMILY,
        code = "CARDS",
        name = "Cards",
        isLeaf = false,
        isActive = true,
        metadata = mapOf("hidden" to true),
        createdAt = Instant.parse("2026-08-09T10:00:00Z"),
    )

    @TestConfiguration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AdminSecurityProperties::class)
    class TestBeans {
        @Bean
        fun limitguard(): Limitguard = mock(Limitguard::class.java)

        @Bean
        fun clientRegistrationRepository(): ClientRegistrationRepository =
            mock(ClientRegistrationRepository::class.java)
    }

    private companion object {
        val productRequest = """
            {
              "code": "CARDS",
              "name": "Cards",
              "category": "CREDIT",
              "nodeType": "FAMILY",
              "parentId": null,
              "isLeaf": false,
              "isActive": true
            }
        """.trimIndent()
    }
}
