package io.github.sepfins.limitguard.admin.web

import io.github.sepfins.limitguard.admin.support.AdminApiIntegrationTest
import io.github.sepfins.limitguard.admin.support.platformAdmin
import io.github.sepfins.limitguard.admin.support.tenantAdmin
import io.github.sepfins.limitguard.admin.support.viewer
import org.hamcrest.Matchers.containsInAnyOrder
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import org.springframework.test.web.servlet.request.RequestPostProcessor
import java.util.UUID

class TenantChannelJourneyTest : AdminApiIntegrationTest() {
    @Test
    fun `tenant admin registers channels, retires one and filters by active state`() {
        val tenantId = createTenant("KBC Bank")
        val mobileId = createChannel(tenantId, "MOBILE", "Mobile app")
        createChannel(tenantId, "ATM", "Cash machines")

        mockMvc.get("/limitguard/admin/api/v1/tenants/$tenantId/channels") {
            param("search", "mobile")
            with(viewer(tenantId))
        }.andExpect {
            status { isOk() }
            jsonPath("$.items.length()") { value(1) }
            jsonPath("$.items[0].id") { value(mobileId.toString()) }
            jsonPath("$.items[0].name") { value("Mobile app") }
            jsonPath("$.items[0].isActive") { value(true) }
        }

        mockMvc.get("/limitguard/admin/api/v1/tenants/$tenantId/channels") {
            param("page", "1")
            param("size", "1")
            with(tenantAdmin(tenantId))
        }.andExpect {
            status { isOk() }
            jsonPath("$.items[0].code") { value("MOBILE") }
            jsonPath("$.totalElements") { value(2) }
            jsonPath("$.totalPages") { value(2) }
        }

        mockMvc.put("/limitguard/admin/api/v1/tenants/$tenantId/channels/$mobileId/active") {
            with(tenantAdmin(tenantId))
            with(csrf())
            contentType = MediaType.APPLICATION_JSON
            content = """{"isActive":false}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.isActive") { value(false) }
        }

        mockMvc.get("/limitguard/admin/api/v1/tenants/$tenantId/channels") {
            param("active", "true")
            with(tenantAdmin(tenantId))
        }.andExpect {
            status { isOk() }
            jsonPath("$.items.length()") { value(1) }
            jsonPath("$.items[0].code") { value("ATM") }
        }
    }

    @Test
    fun `channel groups list their members and refuse duplicates and foreign channels`() {
        val tenantId = createTenant("KBC Bank")
        val mobileId = createChannel(tenantId, "MOBILE", "Mobile app")
        val webId = createChannel(tenantId, "WEB", "Online banking")
        val groupId = createdId(
            mockMvc.post("/limitguard/admin/api/v1/tenants/$tenantId/channel-groups") {
                with(tenantAdmin(tenantId))
                with(csrf())
                contentType = MediaType.APPLICATION_JSON
                content = """{"code":"DIGITAL","name":"Digital channels"}"""
            },
        )
        addMember(tenantId, groupId, mobileId).andExpect { status { isCreated() } }
        addMember(tenantId, groupId, webId).andExpect { status { isCreated() } }

        mockMvc.get("/limitguard/admin/api/v1/tenants/$tenantId/channel-groups") {
            with(viewer(tenantId))
        }.andExpect {
            status { isOk() }
            jsonPath("$.items[0].code") { value("DIGITAL") }
            jsonPath("$.items[0].memberChannelIds") {
                value(containsInAnyOrder(mobileId.toString(), webId.toString()))
            }
        }

        addMember(tenantId, groupId, mobileId).andExpect { status { isConflict() } }
        mockMvc.post("/limitguard/admin/api/v1/tenants/$tenantId/channel-groups") {
            with(tenantAdmin(tenantId))
            with(csrf())
            contentType = MediaType.APPLICATION_JSON
            content = """{"code":"DIGITAL","name":"Another group"}"""
        }.andExpect { status { isConflict() } }

        val otherTenantId = createTenant("Berliner Sparkasse")
        val foreignChannelId = createChannel(otherTenantId, "MOBILE", "Mobile app")
        addMember(tenantId, groupId, foreignChannelId).andExpect { status { isNotFound() } }
    }

    @Test
    fun `duplicates, viewer writes and other tenants are refused`() {
        val tenantId = createTenant("KBC Bank")
        val otherTenantId = createTenant("Berliner Sparkasse")
        val mobileId = createChannel(tenantId, "MOBILE", "Mobile app")

        postChannel(tenantId, tenantAdmin(tenantId), "MOBILE").andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("CONFLICT") }
        }
        postChannel(tenantId, viewer(tenantId), "WEB").andExpect { status { isForbidden() } }
        mockMvc.put("/limitguard/admin/api/v1/tenants/$tenantId/channels/$mobileId/active") {
            with(viewer(tenantId))
            with(csrf())
            contentType = MediaType.APPLICATION_JSON
            content = """{"isActive":false}"""
        }.andExpect { status { isForbidden() } }
        postChannel(tenantId, tenantAdmin(otherTenantId), "WEB").andExpect { status { isForbidden() } }
        mockMvc.get("/limitguard/admin/api/v1/tenants/$tenantId/channels") {
            with(tenantAdmin(otherTenantId))
        }.andExpect { status { isForbidden() } }
        postChannel(tenantId, platformAdmin(), "WEB").andExpect { status { isCreated() } }
    }

    private fun createChannel(tenantId: UUID, code: String, name: String): UUID = createdId(
        mockMvc.post("/limitguard/admin/api/v1/tenants/$tenantId/channels") {
            with(tenantAdmin(tenantId))
            with(csrf())
            contentType = MediaType.APPLICATION_JSON
            content = """{"code":"$code","name":"$name"}"""
        },
    )

    private fun postChannel(
        tenantId: UUID,
        login: RequestPostProcessor,
        code: String,
    ) = mockMvc.post("/limitguard/admin/api/v1/tenants/$tenantId/channels") {
        with(login)
        with(csrf())
        contentType = MediaType.APPLICATION_JSON
        content = """{"code":"$code","name":"Some channel"}"""
    }

    private fun addMember(tenantId: UUID, groupId: UUID, channelId: UUID) =
        mockMvc.post("/limitguard/admin/api/v1/tenants/$tenantId/channel-groups/$groupId/members") {
            with(tenantAdmin(tenantId))
            with(csrf())
            contentType = MediaType.APPLICATION_JSON
            content = """{"channelId":"$channelId"}"""
        }
}
