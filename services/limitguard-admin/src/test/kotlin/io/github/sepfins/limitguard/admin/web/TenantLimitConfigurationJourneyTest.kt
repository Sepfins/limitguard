package io.github.sepfins.limitguard.admin.web

import io.github.sepfins.limitguard.admin.support.AdminApiIntegrationTest
import io.github.sepfins.limitguard.admin.support.tenantAdmin
import io.github.sepfins.limitguard.admin.support.viewer
import org.hamcrest.Matchers.contains
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import org.springframework.test.web.servlet.request.RequestPostProcessor
import java.util.UUID

class TenantLimitConfigurationJourneyTest : AdminApiIntegrationTest() {
    @Test
    fun `a configuration hierarchy is created, filtered, edited and deactivated`() {
        val tenantId = createTenant("KBC Bank")
        val admin = tenantAdmin(tenantId)
        val parentId = createdId(
            postConfiguration(
                tenantId,
                admin,
                """{"code":"DAILY_ALL","name":"Daily total","limitKind":"PERIODIC","period":"DAILY",
                   "currency":"EUR","maxAssignableAmount":"5000.00","defaultAmount":"2500.00"}""",
            ),
        )
        val childId = createdId(
            postConfiguration(
                tenantId,
                admin,
                """{"code":"DAILY_ATM","name":"Daily ATM","limitKind":"PERIODIC","period":"DAILY",
                   "currency":"EUR","maxAssignableAmount":"1000.00","parentConfigurationId":"$parentId",
                   "priority":10,"usageConsumptionMode":"ISOLATED"}""",
            ),
        )
        postConfiguration(
            tenantId,
            admin,
            """{"code":"DAILY_APP","name":"Daily app","limitKind":"PERIODIC","period":"DAILY",
               "currency":"EUR","maxAssignableAmount":"9000.00","parentConfigurationId":"$parentId"}""",
        ).andExpect {
            status { isBadRequest() }
            jsonPath("$.message") { value("child configuration maxAssignableAmount must fit within parent maxAssignableAmount") }
        }
        postConfiguration(
            tenantId,
            admin,
            """{"code":"TXN_ANY","name":"Any payment","limitKind":"PER_TRANSACTION","currency":"EUR",
               "maxAssignableUnlimited":true}""",
        ).andExpect { status { isCreated() } }

        mockMvc.get("/limitguard/admin/api/v1/tenants/$tenantId/limit-configurations") {
            param("parentConfigurationId", parentId.toString())
            with(viewer(tenantId))
        }.andExpect {
            status { isOk() }
            jsonPath("$.items[*].id") { value(contains(childId.toString())) }
            jsonPath("$.items[0].maxAssignableAmount") { value("1000") }
            jsonPath("$.items[0].usageConsumptionMode") { value("ISOLATED") }
        }
        mockMvc.get("/limitguard/admin/api/v1/tenants/$tenantId/limit-configurations") {
            param("search", "any")
            with(viewer(tenantId))
        }.andExpect {
            jsonPath("$.items[*].code") { value(contains("TXN_ANY")) }
            jsonPath("$.items[0].maxAssignableUnlimited") { value(true) }
            jsonPath("$.items[0].maxAssignableAmount") { doesNotExist() }
        }

        mockMvc.put("/limitguard/admin/api/v1/tenants/$tenantId/limit-configurations/$parentId") {
            with(admin)
            with(csrf())
            contentType = MediaType.APPLICATION_JSON
            content = """{"name":"Daily total (all channels)","description":"Bank-wide","defaultAmount":"3000.00"}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.name") { value("Daily total (all channels)") }
            jsonPath("$.description") { value("Bank-wide") }
            jsonPath("$.defaultAmount") { value("3000") }
            jsonPath("$.maxAssignableAmount") { value("5000") }
        }

        mockMvc.put("/limitguard/admin/api/v1/tenants/$tenantId/limit-configurations/$parentId/active") {
            with(admin)
            with(csrf())
            contentType = MediaType.APPLICATION_JSON
            content = """{"isActive":false}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.isActive") { value(false) }
        }
        mockMvc.get("/limitguard/admin/api/v1/tenants/$tenantId/limit-configurations") {
            param("active", "false")
            with(viewer(tenantId))
        }.andExpect {
            jsonPath("$.items[*].id") { value(contains(parentId.toString())) }
        }
    }

    @Test
    fun `unsupported periods, foreign scopes, duplicates and viewers are refused`() {
        val tenantId = createTenant("KBC Bank")
        val otherTenantId = createTenant("Berliner Sparkasse")
        val admin = tenantAdmin(tenantId)
        val foreignChannelId = createdId(
            mockMvc.post("/limitguard/admin/api/v1/tenants/$otherTenantId/channels") {
                with(tenantAdmin(otherTenantId))
                with(csrf())
                contentType = MediaType.APPLICATION_JSON
                content = """{"code":"APP","name":"App"}"""
            },
        )
        val daily = """{"code":"DAILY","name":"Daily","limitKind":"PERIODIC","period":"DAILY","currency":"EUR","maxAssignableAmount":"100"}"""

        postConfiguration(tenantId, admin, daily.replace("\"DAILY\",\"currency\"", "\"WEEKLY\",\"currency\""))
            .andExpect { status { isBadRequest() } }
        postConfiguration(tenantId, admin, daily.replace(""""period":"DAILY",""", ""))
            .andExpect {
                status { isBadRequest() }
                jsonPath("$.message") { value("Period must be specified for LimitKind.PERIODIC.") }
            }
        postConfiguration(
            tenantId,
            admin,
            daily.replace("}", ""","channelScope":"CHANNEL","channelId":"$foreignChannelId"}"""),
        ).andExpect {
            status { isNotFound() }
            jsonPath("$.message") { value("channel not found: $foreignChannelId") }
        }
        postConfiguration(tenantId, admin, daily).andExpect { status { isCreated() } }
        postConfiguration(tenantId, admin, daily).andExpect { status { isConflict() } }
        postConfiguration(tenantId, viewer(tenantId), daily).andExpect { status { isForbidden() } }
        mockMvc.get("/limitguard/admin/api/v1/tenants/$tenantId/limit-configurations") {
            with(tenantAdmin(otherTenantId))
        }.andExpect { status { isForbidden() } }
    }

    private fun postConfiguration(tenantId: UUID, login: RequestPostProcessor, body: String) =
        mockMvc.post("/limitguard/admin/api/v1/tenants/$tenantId/limit-configurations") {
            with(login)
            with(csrf())
            contentType = MediaType.APPLICATION_JSON
            content = body
        }
}
