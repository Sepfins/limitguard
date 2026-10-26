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
import org.springframework.test.web.servlet.request.RequestPostProcessor
import java.util.UUID

class TenantLimitChangePolicyJourneyTest : AdminApiIntegrationTest() {
    @Test
    fun `policies are created, listed in priority order and refused when the core rejects them`() {
        val tenantId = createTenant("KBC Bank")
        val otherTenantId = createTenant("Berliner Sparkasse")
        val admin = tenantAdmin(tenantId)
        val configurationId = createConfiguration(tenantId, admin)
        val foreignConfigurationId = createConfiguration(otherTenantId, tenantAdmin(otherTenantId))
        val youthCap = """{"code":"YOUTH_CAP","name":"Youth cap","decision":"DENY","priority":20,
            "limitConfigurationId":"$configurationId","denialCode":"YOUTH_CAP","denialMessage":"Above the youth cap",
            "conditionExpression":{"decimal_gt":[{"var":"changeRequest.requestedAmount"},"125"]}}"""

        val denyId = createdId(postPolicy(tenantId, admin, youthCap))
        val allowId = createdId(
            postPolicy(
                tenantId,
                admin,
                """{"code":"COOLING_OFF","name":"Cooling-off","decision":"ALLOW","priority":10,
                   "conditionExpression":{"decimal_gt":[{"var":"changeRequest.requestedAmount"},{"var":"changeRequest.beforeAmount"}]},
                   "actionExpression":[{"type":"SET_EFFECTIVE_AT","instant":{"var":"facts.coolingOffEndsAt"}}]}""",
            ),
        )

        mockMvc.get("/limitguard/admin/api/v1/tenants/$tenantId/limit-change-policies") {
            with(viewer(tenantId))
        }.andExpect {
            status { isOk() }
            jsonPath("$.items[*].id") { value(contains(allowId.toString(), denyId.toString())) }
            jsonPath("$.items[0].phase") { value("AUTHORIZATION") }
            jsonPath("$.items[0].actionExpression[0].type") { value("SET_EFFECTIVE_AT") }
            jsonPath("$.items[1].conditionExpression.decimal_gt[1]") { value("125") }
            jsonPath("$.items[1].denialCode") { value("YOUTH_CAP") }
        }
        mockMvc.get("/limitguard/admin/api/v1/tenants/$tenantId/limit-change-policies") {
            param("limitConfigurationId", configurationId.toString())
            with(viewer(tenantId))
        }.andExpect {
            jsonPath("$.items[*].id") { value(contains(denyId.toString())) }
        }

        postPolicy(tenantId, admin, youthCap.replace("YOUTH_CAP\",\"name", "NUMBER\",\"name").replace("\"125\"", "125"))
            .andExpect {
                status { isBadRequest() }
                jsonPath("$.message") {
                    value("conditionExpression.decimal_gt[1] must use a decimal string instead of a JSON number")
                }
            }
        postPolicy(tenantId, admin, youthCap.replace("decimal_gt", "regex"))
            .andExpect {
                status { isBadRequest() }
                jsonPath("$.message") { value("conditionExpression uses unsupported JSON Logic operation: regex") }
            }
        postPolicy(tenantId, admin, youthCap.replace("}}", """},"actionExpression":[{"type":"CAP_REQUESTED_AMOUNT","amount":"125"}]}"""))
            .andExpect {
                status { isBadRequest() }
                jsonPath("$.message") { value("DENY policies must not mutate a request") }
            }
        postPolicy(
            tenantId,
            admin,
            youthCap.replace("\"DENY\"", "\"ALLOW\"").replace("}}", """},"actionExpression":[{"type":"DELAY","hours":4}]}"""),
        ).andExpect {
            status { isBadRequest() }
            jsonPath("$.message") { value("actionExpression[0].type is not supported: DELAY") }
        }
        postPolicy(tenantId, admin, youthCap.replace("\"decision\"", "\"phase\":\"SCHEDULING\",\"decision\""))
            .andExpect {
                status { isBadRequest() }
                jsonPath("$.message") {
                    value("limit change policy phase SCHEDULING is not supported; only AUTHORIZATION is supported")
                }
            }
        postPolicy(tenantId, admin, youthCap.replace(configurationId.toString(), foreignConfigurationId.toString()))
            .andExpect { status { isNotFound() } }
        postPolicy(tenantId, admin, youthCap).andExpect { status { isConflict() } }
        postPolicy(tenantId, viewer(tenantId), youthCap).andExpect { status { isForbidden() } }
    }

    private fun createConfiguration(tenantId: UUID, login: RequestPostProcessor): UUID = createdId(
        mockMvc.post("/limitguard/admin/api/v1/tenants/$tenantId/limit-configurations") {
            with(login)
            with(csrf())
            contentType = MediaType.APPLICATION_JSON
            content = """{"code":"DAILY","name":"Daily","limitKind":"PERIODIC","period":"DAILY",
                "currency":"EUR","maxAssignableAmount":"5000"}"""
        },
    )

    private fun postPolicy(tenantId: UUID, login: RequestPostProcessor, body: String) =
        mockMvc.post("/limitguard/admin/api/v1/tenants/$tenantId/limit-change-policies") {
            with(login)
            with(csrf())
            contentType = MediaType.APPLICATION_JSON
            content = body
        }
}
