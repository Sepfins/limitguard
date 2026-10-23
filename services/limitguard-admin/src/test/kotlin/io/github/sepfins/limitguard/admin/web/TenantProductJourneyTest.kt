package io.github.sepfins.limitguard.admin.web

import io.github.sepfins.limitguard.admin.support.AdminApiIntegrationTest
import io.github.sepfins.limitguard.admin.support.platformAdmin
import io.github.sepfins.limitguard.admin.support.tenantAdmin
import io.github.sepfins.limitguard.admin.support.viewer
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.util.UUID

class TenantProductJourneyTest : AdminApiIntegrationTest() {
    @Test
    fun `tenant admin builds a product tree that later reads return`() {
        val tenantId = createTenant("Retail Italy")
        val familyId = createProduct(
            tenantId,
            """{"code":"CARDS","name":"Cards","category":"CREDIT","nodeType":"FAMILY"}""",
        )
        createProduct(
            tenantId,
            """
                {"code":"VISA_GOLD","name":"Visa Gold","category":"CREDIT","nodeType":"PRODUCT",
                 "parentId":"$familyId","isLeaf":true}
            """.trimIndent(),
        )

        mockMvc.get("/limitguard/admin/api/v1/tenants") {
            param("search", "retail")
            with(platformAdmin())
        }.andExpect {
            status { isOk() }
            jsonPath("$.items[0].id") { value(tenantId.toString()) }
            jsonPath("$.totalElements") { value(1) }
        }

        mockMvc.get("/limitguard/admin/api/v1/tenants/$tenantId/funding-source-products") {
            param("search", "visa")
            with(tenantAdmin(tenantId))
        }.andExpect {
            status { isOk() }
            jsonPath("$.items.length()") { value(1) }
            jsonPath("$.items[0].code") { value("VISA_GOLD") }
            jsonPath("$.items[0].parentId") { value(familyId.toString()) }
            jsonPath("$.items[0].isLeaf") { value(true) }
        }

        mockMvc.get("/limitguard/admin/api/v1/tenants/$tenantId/funding-source-products") {
            param("page", "1")
            param("size", "1")
            with(viewer(tenantId))
        }.andExpect {
            status { isOk() }
            jsonPath("$.items.length()") { value(1) }
            jsonPath("$.page") { value(1) }
            jsonPath("$.totalElements") { value(2) }
            jsonPath("$.totalPages") { value(2) }
        }
    }

    @Test
    fun `duplicate product codes and viewer writes are refused`() {
        val tenantId = createTenant("Retail Italy")
        val cards = """{"code":"CARDS","name":"Cards","category":"CREDIT","nodeType":"FAMILY"}"""
        createProduct(tenantId, cards)

        mockMvc.post("/limitguard/admin/api/v1/tenants/$tenantId/funding-source-products") {
            with(tenantAdmin(tenantId))
            with(csrf())
            contentType = MediaType.APPLICATION_JSON
            content = cards
        }.andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("CONFLICT") }
        }

        mockMvc.post("/limitguard/admin/api/v1/tenants/$tenantId/funding-source-products") {
            with(viewer(tenantId))
            with(csrf())
            contentType = MediaType.APPLICATION_JSON
            content = """{"code":"LOANS","name":"Loans","category":"CREDIT","nodeType":"FAMILY"}"""
        }.andExpect {
            status { isForbidden() }
            jsonPath("$.code") { value("FORBIDDEN") }
        }
    }

    private fun createProduct(tenantId: UUID, request: String): UUID = createdId(
        mockMvc.post("/limitguard/admin/api/v1/tenants/$tenantId/funding-source-products") {
            with(tenantAdmin(tenantId))
            with(csrf())
            contentType = MediaType.APPLICATION_JSON
            content = request
        },
    )
}
