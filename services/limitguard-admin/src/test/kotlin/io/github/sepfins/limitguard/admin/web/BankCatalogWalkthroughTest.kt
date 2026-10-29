package io.github.sepfins.limitguard.admin.web

import com.jayway.jsonpath.JsonPath
import io.github.sepfins.limitguard.admin.support.AdminApiIntegrationTest
import io.github.sepfins.limitguard.admin.support.platformAdmin
import io.github.sepfins.limitguard.api.LimitAmount
import io.github.sepfins.limitguard.api.LimitChangeActorType
import io.github.sepfins.limitguard.api.LimitRuleChangeStatus
import io.github.sepfins.limitguard.api.PartyType
import io.github.sepfins.limitguard.api.commands.CancelLimitRuleChangeCommand
import io.github.sepfins.limitguard.api.commands.LimitChangeActorCommand
import io.github.sepfins.limitguard.api.commands.LimitRuleChangeOperation
import io.github.sepfins.limitguard.api.commands.LimitRuleTargetCommand
import io.github.sepfins.limitguard.api.commands.RegisterPartyProfileCommand
import io.github.sepfins.limitguard.api.commands.RequestLimitRuleChangeCommand
import org.hamcrest.Matchers.contains
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

// The KBC and Frankfurter Sparkasse catalogs, entered through the Admin API the way the console enters them.
class BankCatalogWalkthroughTest : AdminApiIntegrationTest() {
    @Test
    fun `the KBC Bank and Frankfurter Sparkasse catalogs fit the console`() {
        val kbc = buildKbcBank()
        buildFrankfurterSparkasse()

        // A KBC customer changes their daily transfer limit in KBC Mobile, with the host's cooling-off instant attached.
        val customer = limitguard.tenantCatalogService.registerPartyProfile(
            RegisterPartyProfileCommand(kbc.tenantId, PartyType.INDIVIDUAL, firstName = "Lotte", lastName = "Peeters"),
        ).id
        val customerActor = LimitChangeActorCommand(LimitChangeActorType.PARTY_PROFILE, customer)
        val coolingOffEndsAt = Instant.now().plus(Duration.ofHours(4)).truncatedTo(ChronoUnit.SECONDS)
        fun request(amount: String) = limitguard.limitChangeService.requestLimitRuleChange(
            RequestLimitRuleChangeCommand(
                tenantId = kbc.tenantId,
                requestedBy = customerActor,
                requesterPartyProfileId = customer,
                target = LimitRuleTargetCommand(customer, kbc.digitalTransferDaily),
                operation = LimitRuleChangeOperation.SetAmount(LimitAmount.Bounded(BigDecimal(amount))),
                requestChannelId = kbc.mobile,
                policyContext = mapOf("coolingOffEndsAt" to coolingOffEndsAt.toString()),
            ),
        )

        assertEquals(LimitRuleChangeStatus.APPLIED, request("1000").status)
        val increase = request("2500")
        assertEquals(LimitRuleChangeStatus.PENDING_EFFECTIVE_DATE, increase.status)
        assertEquals(coolingOffEndsAt, increase.effectiveAt)
        // The customer withdraws the increase, which frees the rule for the next request.
        limitguard.limitChangeService.cancelLimitRuleChange(
            CancelLimitRuleChangeCommand(kbc.tenantId, increase.id, customerActor),
        )
        assertEquals(LimitRuleChangeStatus.APPLIED, request("600").status)
    }

    private class Kbc(val tenantId: UUID, val mobile: UUID, val digitalTransferDaily: UUID)

    private fun buildKbcBank(): Kbc {
        val tenant = createTenant("KBC Bank", "ALLOW")
        val products = tenant.products(
            """{"code":"PAYMENT_ACCOUNTS","name":"Payment accounts","nodeType":"FAMILY","category":"TRANSACTIONAL"}""",
            """{"code":"CURRENT_ACCOUNT","name":"Current account","nodeType":"PRODUCT","category":"TRANSACTIONAL","parent":"PAYMENT_ACCOUNTS","isLeaf":true}""",
            """{"code":"YOUTH_ACCOUNT","name":"Youth account (K'Ching)","nodeType":"PRODUCT","category":"TRANSACTIONAL","parent":"PAYMENT_ACCOUNTS","isLeaf":true}""",
            """{"code":"CREDIT_CARD","name":"Credit card","nodeType":"PRODUCT","category":"CREDIT","isLeaf":true}""",
        )
        val channels = tenant.channels(
            "KBC_MOBILE" to "KBC Mobile",
            "KBC_TOUCH" to "KBC Touch",
            "BRANCH" to "Branch",
            "ATM" to "ATM",
            "CARD_POS" to "Card terminal",
        )
        val groups = mapOf("DIGITAL" to tenant.channelGroup("DIGITAL", "Self-service channels", channels, "KBC_MOBILE", "KBC_TOUCH"))
        val operations = tenant.operationTypes(
            """{"code":"TRANSFER","name":"Transfer"}""",
            """{"code":"TRANSFER_OWN_ACCOUNT","name":"Transfer between own accounts","parent":"TRANSFER","isLeaf":true}""",
            """{"code":"TRANSFER_THIRD_PARTY","name":"Transfer to another account","parent":"TRANSFER","isLeaf":true}""",
            """{"code":"TRANSFER_KNOWN_BENEFICIARY","name":"Transfer to a known beneficiary","parent":"TRANSFER","isLeaf":true}""",
            """{"code":"TRANSFER_NEW_BENEFICIARY","name":"Transfer to a new beneficiary","parent":"TRANSFER","isLeaf":true}""",
            """{"code":"CASH_WITHDRAWAL","name":"Cash withdrawal","isLeaf":true}""",
            """{"code":"CARD_PAYMENT","name":"Card payment","isLeaf":true}""",
        )
        val scopes = Scopes(products, channels, groups, operations)
        val configurations = tenant.configurations(
            scopes.daily("DIGITAL_TRANSFER_DAILY", "Digital transfers per day", ALL, "group DIGITAL", "TRANSFER_THIRD_PARTY", "2500", "2500"),
            scopes.daily("CARD_CASH_DAILY", "Card cash per day", "product CURRENT_ACCOUNT", "channel ATM", "CASH_WITHDRAWAL", "2500", "650"),
            scopes.perTransaction("CONTACTLESS_NO_PIN", "Contactless without PIN", ALL, "channel CARD_POS", "CARD_PAYMENT", "50", "50"),
            scopes.perTransaction("BUSINESS_NEW_BENEFICIARY_TX", "New beneficiary per transfer", ALL, "group DIGITAL", "TRANSFER_NEW_BENEFICIARY", "2500", "2500"),
            scopes.daily("BUSINESS_NEW_BENEFICIARY_DAILY", "New beneficiary per day", ALL, "group DIGITAL", "TRANSFER_NEW_BENEFICIARY", "2500", "2500"),
            scopes.perTransaction("BUSINESS_KNOWN_BENEFICIARY_TX", "Known beneficiary per transfer", ALL, "group DIGITAL", "TRANSFER_KNOWN_BENEFICIARY", "10000", "10000"),
            scopes.daily("BUSINESS_KNOWN_BENEFICIARY_DAILY", "Known beneficiary per day", ALL, "group DIGITAL", "TRANSFER_KNOWN_BENEFICIARY", "10000", "10000"),
        )

        val channelIds = tenant.listedChannelIds()
        val digitalTransferDaily = configurations.getValue("DIGITAL_TRANSFER_DAILY")
        tenant.policy(
            """{"code":"YOUTH_TRANSFER_CAP","name":"Youth transfer cap","decision":"DENY","priority":5,
               "limitConfigurationId":"$digitalTransferDaily","denialMessage":"Minors may transfer at most 125 EUR a day",
               "conditionExpression":{"and":[
                 {"==":[{"var":"facts.ownerIsMinor"},true]},
                 {"decimal_gt":[{"var":"changeRequest.requestedAmount"},"125"]}]}}""",
        )
        tenant.policy(
            """{"code":"TRANSFER_INCREASE_COOLING_OFF","name":"Transfer increase cooling-off","decision":"ALLOW","priority":10,
               "limitConfigurationId":"$digitalTransferDaily",
               "conditionExpression":{"and":[
                 {"==":[{"var":"changeRequest.changeType"},"UPDATE"]},
                 {"in":[{"var":"changeRequest.requestActorContext.requestChannelId"},
                   ["${channelIds.getValue("KBC_MOBILE")}","${channelIds.getValue("KBC_TOUCH")}"]]},
                 {"decimal_gt":[{"var":"changeRequest.requestedAmount"},{"var":"changeRequest.beforeAmount"}]}]},
               "actionExpression":[{"type":"SET_EFFECTIVE_AT","instant":{"var":"facts.coolingOffEndsAt"}}]}""",
        )
        tenant.policy("""{"code":"SELF_SERVICE","name":"Self-service","decision":"ALLOW","priority":100}""")

        tenant.expectCounts(products = 4, channels = 5, channelGroups = 1, operationTypes = 7, configurations = 7)
        tenant.expectPolicyOrder("YOUTH_TRANSFER_CAP", "TRANSFER_INCREASE_COOLING_OFF", "SELF_SERVICE")
        return Kbc(tenant.id, channelIds.getValue("KBC_MOBILE"), digitalTransferDaily)
    }

    private fun buildFrankfurterSparkasse() {
        val tenant = createTenant("Frankfurter Sparkasse", "DENY")
        val products = tenant.products(
            """{"code":"GIROKONTO","name":"Girokonto","nodeType":"PRODUCT","category":"TRANSACTIONAL","isLeaf":true}""",
            """{"code":"TAGESGELD","name":"Tagesgeld","nodeType":"PRODUCT","category":"SAVINGS","isLeaf":true}""",
            """{"code":"GESCHAEFTSKONTO","name":"Geschäftskonto","nodeType":"PRODUCT","category":"TRANSACTIONAL","isLeaf":true}""",
        )
        val channels = tenant.channels(
            "ONLINE_BANKING" to "Online-Banking",
            "SPARKASSEN_APP" to "App Sparkasse",
            "BRANCH" to "Filiale",
            "ATM_OWN" to "Eigener Geldautomat",
            "ATM_OTHER_DE" to "Fremder Geldautomat Inland",
            "ATM_ABROAD" to "Geldautomat Ausland",
            "CARD_POS" to "Kassenterminal",
            "ECOMMERCE" to "Online-Handel",
            "WERO" to "Wero",
        )
        val groups = mapOf(
            "DIGITAL" to tenant.channelGroup("DIGITAL", "Online-Banking und App", channels, "ONLINE_BANKING", "SPARKASSEN_APP"),
            "ATM_FOREIGN" to tenant.channelGroup("ATM_FOREIGN", "Fremde Geldautomaten", channels, "ATM_OTHER_DE", "ATM_ABROAD"),
            "CARD_PAYMENTS" to tenant.channelGroup("CARD_PAYMENTS", "Kartenzahlungen", channels, "CARD_POS", "ECOMMERCE"),
        )
        val operations = tenant.operationTypes(
            """{"code":"TRANSFER","name":"Überweisung"}""",
            """{"code":"TRANSFER_OWN_ACCOUNT","name":"Übertrag zwischen eigenen Konten","parent":"TRANSFER","isLeaf":true}""",
            """{"code":"TRANSFER_THIRD_PARTY","name":"Überweisung an Dritte","parent":"TRANSFER","isLeaf":true}""",
            """{"code":"TRANSFER_INSTANT","name":"Echtzeitüberweisung","parent":"TRANSFER","isLeaf":true}""",
            """{"code":"CASH_WITHDRAWAL","name":"Bargeldauszahlung","isLeaf":true}""",
            """{"code":"CARD_PAYMENT","name":"Kartenzahlung","isLeaf":true}""",
            """{"code":"WERO_PAYMENT","name":"Wero-Zahlung","isLeaf":true}""",
        )
        val scopes = Scopes(products, channels, groups, operations)
        // Each Sparkasse sets its own online limit; 3,000 is an onboarding example.
        val configurations = tenant.configurations(
            scopes.daily("CARD_CASH_OWN_ATM_DAILY", "Bargeld am eigenen Geldautomaten", "product GIROKONTO", "channel ATM_OWN", "CASH_WITHDRAWAL", "2000", "2000"),
            scopes.daily("CARD_CASH_FOREIGN_ATM_DAILY", "Bargeld an fremden Geldautomaten", "product GIROKONTO", "group ATM_FOREIGN", "CASH_WITHDRAWAL", "1000", "1000"),
            scopes.daily("CARD_PAYMENT_DAILY", "Kartenzahlungen pro Tag", "product GIROKONTO", "group CARD_PAYMENTS", "CARD_PAYMENT", "5000", "5000"),
            scopes.perTransaction("CONTACTLESS_NO_PIN", "Kontaktlos ohne PIN", ALL, "channel CARD_POS", "CARD_PAYMENT", "50", "50"),
            scopes.daily("ONLINE_TRANSFER_DAILY", "Online-Banking-Limit", ALL, "group DIGITAL", "TRANSFER", "10000", "3000"),
            scopes.daily("TAGESGELD_TRANSFER", "Übertrag vom Tagesgeld", "product TAGESGELD", "group DIGITAL", "TRANSFER_OWN_ACCOUNT", null, null,
                priority = 50, usage = "ISOLATED"),
            scopes.perTransaction("INSTANT_TX", "Echtzeitüberweisung pro Auftrag", ALL, "group DIGITAL", "TRANSFER_INSTANT", null, null),
            scopes.daily("INSTANT_DAILY", "Echtzeitüberweisung pro Tag", ALL, "group DIGITAL", "TRANSFER_INSTANT", null, null),
            scopes.perTransaction("WERO_TX", "Wero pro Zahlung", ALL, "channel WERO", "WERO_PAYMENT", "1000", "1000"),
            scopes.daily("WERO_DAILY", "Wero pro Tag", ALL, "channel WERO", "WERO_PAYMENT", "2000", "2000"),
        )

        val onlineTransferDaily = configurations.getValue("ONLINE_TRANSFER_DAILY")
        tenant.policy(
            """{"code":"PROXY_MAY_ONLY_LOWER","name":"Bevollmächtigte nur herabsetzen","decision":"DENY","priority":10,
               "limitConfigurationId":"$onlineTransferDaily","denialMessage":"Bevollmächtigte dürfen das Limit nur herabsetzen",
               "conditionExpression":{"and":[
                 {"!=":[{"var":"changeRequest.requestedBy.partyProfileId"},{"var":"changeRequest.targetOwnerPartyProfileId"}]},
                 {"!=":[{"var":"facts.proxyMayRaise"},true]},
                 {"or":[
                   {"==":[{"var":"changeRequest.changeType"},"CREATE"]},
                   {"decimal_gt":[{"var":"changeRequest.requestedAmount"},{"var":"changeRequest.beforeAmount"}]}]}]}}""",
        )
        tenant.policy(
            """{"code":"ABOVE_AGREED_MAXIMUM","name":"Über dem Maximallimit","decision":"REQUIRE_APPROVAL","priority":20,
               "limitConfigurationId":"$onlineTransferDaily",
               "conditionExpression":{"decimal_gt":[{"var":"changeRequest.requestedAmount"},{"var":"facts.agreedMaximum"}]}}""",
        )
        tenant.policy("""{"code":"SELF_SERVICE","name":"Selbstbedienung","decision":"ALLOW","priority":100}""")

        tenant.expectCounts(products = 3, channels = 9, channelGroups = 3, operationTypes = 7, configurations = 10)
        tenant.expectPolicyOrder("PROXY_MAY_ONLY_LOWER", "ABOVE_AGREED_MAXIMUM", "SELF_SERVICE")
    }

    private fun createTenant(name: String, noMatchingRuleBehavior: String) = Tenant(
        createdId(
            mockMvc.post("/limitguard/admin/api/v1/tenants") {
                with(platformAdmin())
                with(csrf())
                contentType = MediaType.APPLICATION_JSON
                content = """{"name":"$name","noMatchingRuleBehavior":"$noMatchingRuleBehavior"}"""
            },
        ),
    )

    /** Resolves scope words ("group DIGITAL", "product TAGESGELD") to request fields. */
    private class Scopes(
        val products: Map<String, UUID>,
        val channels: Map<String, UUID>,
        val groups: Map<String, UUID>,
        val operations: Map<String, UUID>,
    ) {
        fun daily(code: String, name: String, funding: String, channel: String, operation: String, max: String?, default: String?,
                  priority: Int = 100, usage: String = "CASCADING") =
            configuration(code, name, """"limitKind":"PERIODIC","period":"DAILY"""", funding, channel, operation, max, default, priority, usage)

        fun perTransaction(code: String, name: String, funding: String, channel: String, operation: String, max: String?, default: String?) =
            configuration(code, name, """"limitKind":"PER_TRANSACTION"""", funding, channel, operation, max, default, 100, "CASCADING")

        private fun configuration(code: String, name: String, kind: String, funding: String, channel: String, operation: String,
                                  max: String?, default: String?, priority: Int, usage: String): Pair<String, String> {
            val fundingScope = if (funding == ALL) {
                """"fundingScope":"ALL_FUNDING_SOURCES""""
            } else {
                """"fundingScope":"FUNDING_SOURCE_PRODUCT","fundingSourceProductId":"${products.getValue(funding.removePrefix("product "))}""""
            }
            val channelScope = if (channel.startsWith("group ")) {
                """"channelScope":"CHANNEL_GROUP","channelGroupId":"${groups.getValue(channel.removePrefix("group "))}""""
            } else {
                """"channelScope":"CHANNEL","channelId":"${channels.getValue(channel.removePrefix("channel "))}""""
            }
            val maximum = if (max == null) """"maxAssignableUnlimited":true""" else """"maxAssignableAmount":"$max""""
            val defaultAmount = default?.let { ""","defaultAmount":"$it"""" } ?: ""
            return code to """{"code":"$code","name":"$name",$kind,"currency":"EUR",$fundingScope,$channelScope,
                "operationTypeId":"${operations.getValue(operation)}",$maximum$defaultAmount,
                "priority":$priority,"usageConsumptionMode":"$usage"}"""
        }
    }

    private inner class Tenant(val id: UUID) {
        private val base = "/limitguard/admin/api/v1/tenants/$id"

        /** Each body may name an earlier node's code as "parent". */
        fun products(vararg bodies: String) = tree("funding-source-products", bodies)

        fun operationTypes(vararg bodies: String) = tree("operation-types", bodies)

        fun channels(vararg channels: Pair<String, String>) = channels.associate { (code, name) ->
            code to post("channels", """{"code":"$code","name":"$name"}""")
        }

        fun channelGroup(code: String, name: String, channels: Map<String, UUID>, vararg members: String): UUID {
            val groupId = post("channel-groups", """{"code":"$code","name":"$name"}""")
            members.forEach { member ->
                mockMvc.post("$base/channel-groups/$groupId/members") {
                    with(platformAdmin())
                    with(csrf())
                    contentType = MediaType.APPLICATION_JSON
                    content = """{"channelId":"${channels.getValue(member)}"}"""
                }.andExpect { status { isCreated() } }
            }
            return groupId
        }

        fun configurations(vararg configurations: Pair<String, String>) = configurations.associate { (code, body) ->
            code to post("limit-configurations", body)
        }

        fun policy(body: String) = post("limit-change-policies", body)

        /** Channel ids as the channels page shows them, for pasting into a policy condition. */
        fun listedChannelIds(): Map<String, UUID> = list("channels").associate { channel ->
            channel.getValue("code") as String to UUID.fromString(channel.getValue("id") as String)
        }

        fun expectCounts(products: Int, channels: Int, channelGroups: Int, operationTypes: Int, configurations: Int) {
            assertEquals(products, list("funding-source-products").size)
            assertEquals(channels, list("channels").size)
            assertEquals(channelGroups, list("channel-groups").size)
            assertEquals(List(channelGroups) { 2 }, list("channel-groups").map { (it.getValue("memberChannelIds") as List<*>).size })
            assertEquals(operationTypes, list("operation-types").size)
            assertEquals(configurations, list("limit-configurations").size)
        }

        fun expectPolicyOrder(vararg codes: String) {
            mockMvc.get("$base/limit-change-policies") { with(platformAdmin()) }.andExpect {
                status { isOk() }
                jsonPath("$.items[*].code") { value(contains(*codes)) }
            }
        }

        private fun tree(path: String, bodies: Array<out String>): Map<String, UUID> {
            val ids = mutableMapOf<String, UUID>()
            bodies.forEach { body ->
                val node = JsonPath.read<Map<String, Any?>>(body, "$")
                val code = node.getValue("code") as String
                val parent = node["parent"] as String?
                val request = if (parent == null) body else body.replace(""""parent":"$parent"""", """"parentId":"${ids.getValue(parent)}"""")
                ids[code] = post(path, request)
            }
            return ids
        }

        private fun post(path: String, body: String): UUID = createdId(
            mockMvc.post("$base/$path") {
                with(platformAdmin())
                with(csrf())
                contentType = MediaType.APPLICATION_JSON
                content = body
            },
        )

        private fun list(path: String): List<Map<String, Any?>> {
            val response = mockMvc.get("$base/$path") {
                param("size", "100")
                with(platformAdmin())
            }.andExpect { status { isOk() } }.andReturn().response.contentAsString
            assertEquals(JsonPath.read<Int>(response, "$.totalElements"), JsonPath.read<List<Any>>(response, "$.items").size)
            return JsonPath.read(response, "$.items")
        }
    }

    private companion object {
        const val ALL = "all"
    }
}
