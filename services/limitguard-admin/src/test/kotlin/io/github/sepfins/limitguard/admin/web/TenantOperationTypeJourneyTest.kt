package io.github.sepfins.limitguard.admin.web

import io.github.sepfins.limitguard.admin.support.AdminApiIntegrationTest
import io.github.sepfins.limitguard.admin.support.platformAdmin
import io.github.sepfins.limitguard.admin.support.tenantAdmin
import io.github.sepfins.limitguard.admin.support.viewer
import org.hamcrest.Matchers.contains
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.request.RequestPostProcessor
import java.util.UUID

class TenantOperationTypeJourneyTest : AdminApiIntegrationTest() {
    @Test
    fun `operation types list as a tree with the global ones a platform admin shares`() {
        val tenantId = createTenant("KBC Bank")
        val otherTenantId = createTenant("Berliner Sparkasse")
        val transferId = createdId(postOperation(tenantId, tenantAdmin(tenantId), """{"code":"TRANSFER","name":"Transfer"}"""))
        postOperation(
            tenantId,
            tenantAdmin(tenantId),
            """{"code":"TRANSFER_THIRD_PARTY","name":"Transfer to third party","parentId":"$transferId","isLeaf":true}""",
        )
        postOperation(
            tenantId,
            tenantAdmin(tenantId),
            """{"code":"TRANSFER_OWN","name":"Transfer to own account","parentId":"$transferId","isLeaf":true}""",
        )
        postOperation(tenantId, tenantAdmin(tenantId), """{"code":"PAYMENT","name":"Payment","isGlobal":true}""")
            .andExpect { status { isForbidden() } }
        val cashId = createdId(
            postOperation(otherTenantId, platformAdmin(), """{"code":"CASH","name":"Cash withdrawal","isGlobal":true}"""),
        )

        mockMvc.get("/limitguard/admin/api/v1/tenants/$tenantId/operation-types") {
            with(viewer(tenantId))
        }.andExpect {
            status { isOk() }
            jsonPath("$.items[*].code") { value(contains("CASH", "TRANSFER", "TRANSFER_OWN", "TRANSFER_THIRD_PARTY")) }
            jsonPath("$.items[0].isGlobal") { value(true) }
            jsonPath("$.items[1].isGlobal") { value(false) }
            jsonPath("$.items[2].parentId") { value(transferId.toString()) }
            jsonPath("$.items[2].isLeaf") { value(true) }
        }

        mockMvc.get("/limitguard/admin/api/v1/tenants/$tenantId/operation-types") {
            param("leaf", "false")
            param("search", "a")
            param("size", "1")
            param("page", "1")
            with(tenantAdmin(tenantId))
        }.andExpect {
            status { isOk() }
            jsonPath("$.items[*].code") { value(contains("TRANSFER")) }
            jsonPath("$.totalElements") { value(2) }
        }

        mockMvc.get("/limitguard/admin/api/v1/tenants/$otherTenantId/operation-types") {
            with(viewer(otherTenantId))
        }.andExpect {
            status { isOk() }
            jsonPath("$.items[*].id") { value(contains(cashId.toString())) }
        }
    }

    @Test
    fun `nesting under a leaf, duplicate codes and foreign parents are refused`() {
        val tenantId = createTenant("KBC Bank")
        val otherTenantId = createTenant("Berliner Sparkasse")
        val leafId = createdId(
            postOperation(tenantId, tenantAdmin(tenantId), """{"code":"ATM","name":"Cash machine","isLeaf":true}"""),
        )
        val foreignId = createdId(
            postOperation(otherTenantId, tenantAdmin(otherTenantId), """{"code":"TRANSFER","name":"Transfer"}"""),
        )

        postOperation(tenantId, tenantAdmin(tenantId), """{"code":"ATM_EU","name":"EU","parentId":"$leafId"}""")
            .andExpect {
                status { isBadRequest() }
                jsonPath("$.message") { value("Cannot add a child beneath a declared operation leaf") }
            }
        postOperation(tenantId, tenantAdmin(tenantId), """{"code":"ATM","name":"Again"}""")
            .andExpect { status { isConflict() } }
        postOperation(tenantId, tenantAdmin(tenantId), """{"code":"SEPA","name":"SEPA","parentId":"$foreignId"}""")
            .andExpect { status { isNotFound() } }
        postOperation(tenantId, viewer(tenantId), """{"code":"SEPA","name":"SEPA"}""")
            .andExpect { status { isForbidden() } }
    }

    private fun postOperation(tenantId: UUID, login: RequestPostProcessor, body: String) =
        mockMvc.post("/limitguard/admin/api/v1/tenants/$tenantId/operation-types") {
            with(login)
            with(csrf())
            contentType = MediaType.APPLICATION_JSON
            content = body
        }
}
