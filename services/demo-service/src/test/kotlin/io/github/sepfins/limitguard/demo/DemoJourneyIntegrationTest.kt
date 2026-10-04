package io.github.sepfins.limitguard.demo

import io.github.sepfins.limitguard.api.LimitChangeActorType
import io.github.sepfins.limitguard.api.Limitguard
import io.github.sepfins.limitguard.api.queries.GetLimitChangeQuery
import io.github.sepfins.limitguard.demo.application.DemoScenarioService
import io.github.sepfins.limitguard.demo.application.DueLimitChangeJob
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@Tag("database")
@Testcontainers
@SpringBootTest(properties = ["limitguard.demo.cooling-off=1s", "limitguard.demo.due-pass-interval=1h"])
@AutoConfigureMockMvc
class DemoJourneyIntegrationTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val objectMapper: ObjectMapper,
    private val jdbcClient: JdbcClient,
    private val scenarioService: DemoScenarioService,
    private val dueLimitChangeJob: DueLimitChangeJob,
    private val limitguard: Limitguard,
) {
    @Test
    fun `customer journey covers lifecycle limits idempotency repair balances and errors`() {
        mockMvc.get("/limitguard/payments/").andExpect {
            status { isOk() }
            forwardedUrl("/limitguard/payments/index.html")
        }
        assertNotNull(
            javaClass.classLoader.getResource("static/limitguard/payments/index.html"),
            "Payment Lab must be packaged with the demo service",
        )

        val scenarioResult = mockMvc.get("/limitguard/demo/api/v1/scenario") {
            header("X-Correlation-ID", "journey-correlation")
        }.andExpect {
            status { isOk() }
            header { string("X-Correlation-ID", "journey-correlation") }
            jsonPath("$.customer.displayName") { value("Emma Clarke") }
            jsonPath("$.accountIds.length()") { value(2) }
            jsonPath("$.cardIds.length()") { value(1) }
        }.andReturn()
        val scenario = json(scenarioResult)
        val accountId = UUID.fromString(scenario["accountIds"][0].asText())
        val cardId = UUID.fromString(scenario["cardIds"][0].asText())
        val scenarioId = scenarioService.current().id

        val initialAccount = getJson("/limitguard/demo/api/v1/customer/accounts/$accountId")
        val openingBalance = initialAccount["bookedBalance"]["amount"].decimal()
        // The seeded activity leaves a hold open, so available starts below booked.
        val openingAvailable = initialAccount["availableBalance"]["amount"].decimal()
        val seededPayments = getJson("/limitguard/demo/api/v1/customer/payments")
        assertEquals(7, seededPayments.size())
        assertEquals("DECLINED", seededPayments.first { it["externalPaymentId"].asText() == "demo-seed-006" }["displayStatus"].asText())

        val cardAuthorizationBody = authorizationBody(
            externalPaymentId = "journey-card-001",
            operation = "CARD_PURCHASE",
            accountId = accountId,
            cardId = cardId,
            amount = "85.40",
            description = "Corner Grocer",
            category = "GROCERIES",
        )
        val authorized = postJson(
            "/limitguard/demo/api/v1/integrations/payment-authorizations",
            "journey-card-auth-001",
            cardAuthorizationBody,
        )
        assertEquals("ALLOWED", authorized["decision"].asText())
        assertEquals("AUTHORIZED", authorized["transactionStatus"].asText())
        assertEquals(false, authorized["replayed"].asBoolean())

        val heldAccount = getJson("/limitguard/demo/api/v1/customer/accounts/$accountId")
        assertEquals(openingBalance, heldAccount["bookedBalance"]["amount"].decimal())
        assertEquals(openingAvailable - 85.40.toBigDecimal(), heldAccount["availableBalance"]["amount"].decimal())

        val replay = postJson(
            "/limitguard/demo/api/v1/integrations/payment-authorizations",
            "journey-card-auth-001",
            cardAuthorizationBody,
        )
        assertEquals(true, replay["replayed"].asBoolean())
        assertEquals(authorized["authorizationDecisionId"].asText(), replay["authorizationDecisionId"].asText())

        mockMvc.post("/limitguard/demo/api/v1/integrations/payment-authorizations") {
            contentType = MediaType.APPLICATION_JSON
            header("Idempotency-Key", "journey-card-auth-001")
            content = authorizationBody(
                externalPaymentId = "journey-card-002",
                operation = "CARD_PURCHASE",
                accountId = accountId,
                cardId = cardId,
                amount = "90.00",
                description = "Changed request",
                category = "SHOPPING",
            )
        }.andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("CONFLICT") }
            jsonPath("$.correlationId") { isNotEmpty() }
        }

        val captureBody = objectMapper.writeValueAsString(mapOf("amount" to "85.40", "reason" to "Completed"))
        val capture = postJson(
            "/limitguard/demo/api/v1/integrations/payments/journey-card-001/captures",
            "journey-card-capture-001",
            captureBody,
        )
        assertEquals("CAPTURED", capture["transactionStatus"].asText())
        assertEquals(false, capture["replayed"].asBoolean())
        val captureReplay = postJson(
            "/limitguard/demo/api/v1/integrations/payments/journey-card-001/captures",
            "journey-card-capture-001",
            captureBody,
        )
        assertEquals(true, captureReplay["replayed"].asBoolean())
        assertEquals(capture["transactionEventId"].asText(), captureReplay["transactionEventId"].asText())

        val capturedAccount = getJson("/limitguard/demo/api/v1/customer/accounts/$accountId")
        assertEquals(openingBalance - 85.40.toBigDecimal(), capturedAccount["bookedBalance"]["amount"].decimal())
        assertEquals(openingAvailable - 85.40.toBigDecimal(), capturedAccount["availableBalance"]["amount"].decimal())

        val cardLimitId = getJson("/limitguard/demo/api/v1/customer/limits").first {
            it["operation"].asText() == "CARD_PURCHASE"
        }["id"].asText()
        mockMvc.patch("/limitguard/demo/api/v1/customer/limits/$cardLimitId") {
            contentType = MediaType.APPLICATION_JSON
            header("Idempotency-Key", "journey-limit-policy-denial-001")
            content = objectMapper.writeValueAsString(mapOf("amount" to "50.00"))
        }.andExpect {
            status { isUnprocessableContent() }
            jsonPath("$.code") { value("POLICY_DENIED") }
            jsonPath("$.message") { value("The new limit cannot be below current spending and reservations") }
            jsonPath("$.correlationId") { isNotEmpty() }
        }

        val firstAtm = postJson(
            "/limitguard/demo/api/v1/integrations/payment-authorizations",
            "journey-atm-auth-001",
            authorizationBody(
                "journey-atm-001",
                "ATM_WITHDRAWAL",
                accountId,
                cardId,
                "400.00",
                "Piazza Cordusio ATM",
                "CASH",
            ),
        )
        assertEquals("ALLOWED", firstAtm["decision"].asText())
        val cumulativeDenial = postJson(
            "/limitguard/demo/api/v1/integrations/payment-authorizations",
            "journey-atm-auth-002",
            authorizationBody(
                "journey-atm-002",
                "ATM_WITHDRAWAL",
                accountId,
                cardId,
                "150.00",
                "Station Road ATM",
                "CASH",
            ),
        )
        assertEquals("DENIED", cumulativeDenial["decision"].asText())
        assertEquals("PERIODIC_LIMIT_EXCEEDED", cumulativeDenial["violations"][0]["code"].asText())
        postJson(
            "/limitguard/demo/api/v1/integrations/payments/journey-atm-001/releases",
            "journey-atm-release-001",
            objectMapper.writeValueAsString(mapOf("reason" to "Cash not dispensed")),
        ).also { assertEquals("VOIDED", it["transactionStatus"].asText()) }

        val transferDenial = postJson(
            "/limitguard/demo/api/v1/integrations/payment-authorizations",
            "journey-transfer-auth-001",
            authorizationBody(
                "journey-transfer-001",
                "BANK_TRANSFER",
                accountId,
                null,
                "2600.00",
                "Apartment deposit",
                "TRANSFER",
            ),
        )
        assertEquals("DENIED", transferDenial["decision"].asText())
        assertEquals("PER_TRANSACTION_LIMIT_EXCEEDED", transferDenial["violations"][0]["code"].asText())

        val limitsBefore = getJson("/limitguard/demo/api/v1/customer/limits")
        val transferLimit = limitsBefore.first {
            it["operation"].asText() == "BANK_TRANSFER" && it["limitKind"].asText() == "PER_TRANSACTION"
        }
        val transferLimitId = transferLimit["id"].asText()
        val changedLimit = patchJson(
            "/limitguard/demo/api/v1/customer/limits/$transferLimitId",
            "journey-limit-001",
            objectMapper.writeValueAsString(mapOf("amount" to "3000.00")),
        )
        assertEquals("APPLIED", changedLimit["status"].asText())
        assertEquals("3000.00", changedLimit["limit"]["currentAmount"]["amount"].asText())
        assertEquals(transferLimit["version"].asInt() + 1, changedLimit["limit"]["version"].asInt())
        val currentScenario = scenarioService.current()
        val changeRequestId = jdbcClient.sql(
            "SELECT id FROM limit_rule_change_request WHERE tenant_id = :tenantId AND idempotency_key = :key",
        ).param("tenantId", currentScenario.tenantId)
            .param("key", "demo-limit:$transferLimitId:journey-limit-001")
            .query(UUID::class.java).single()
        val change = limitguard.limitInquiryService.getLimitChange(GetLimitChangeQuery(
            requireNotNull(currentScenario.tenantId), changeRequestId,
        )).request
        assertEquals(LimitChangeActorType.PARTY_PROFILE, change.requestedBy.actorType)
        assertEquals(currentScenario.customerPartyProfileId, change.requestedBy.partyProfileId)
        assertEquals(currentScenario.customerPartyProfileId, change.requesterPartyProfileId)
        assertEquals(currentScenario.ownerPartyProfileId, change.targetOwnerPartyProfileId)
        assertEquals(currentScenario.authorityDelegationId, change.authorityDelegationId)
        assertEquals(currentScenario.mandateId, change.targetMandateId)
        val repeatedLimitChange = patchJson(
            "/limitguard/demo/api/v1/customer/limits/$transferLimitId",
            "journey-limit-001",
            objectMapper.writeValueAsString(mapOf("amount" to "3000.00")),
        )
        assertEquals("APPLIED", repeatedLimitChange["status"].asText())
        assertEquals(changedLimit["limit"]["version"].asInt(), repeatedLimitChange["limit"]["version"].asInt())

        mockMvc.patch("/limitguard/demo/api/v1/customer/limits/$transferLimitId") {
            contentType = MediaType.APPLICATION_JSON
            header("Idempotency-Key", "journey-limit-001")
            content = objectMapper.writeValueAsString(mapOf("amount" to "3050.00"))
        }.andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("IDEMPOTENCY_CONFLICT") }
        }

        val atmLimitId = limitsBefore.first { it["operation"].asText() == "ATM_WITHDRAWAL" }["id"].asText()
        mockMvc.patch("/limitguard/demo/api/v1/customer/limits/$atmLimitId") {
            contentType = MediaType.APPLICATION_JSON
            header("Idempotency-Key", "journey-atm-limit-raise-001")
            content = objectMapper.writeValueAsString(mapOf("amount" to "600.00"))
        }.andExpect {
            status { isUnprocessableContent() }
            jsonPath("$.code") { value("POLICY_DENIED") }
            jsonPath("$.message") {
                value("As an authorized user you can only lower the cash limit. Ask the account holder to raise it.")
            }
        }
        val loweredAtm = patchJson(
            "/limitguard/demo/api/v1/customer/limits/$atmLimitId",
            "journey-atm-limit-lower-001",
            objectMapper.writeValueAsString(mapOf("amount" to "450.00")),
        )
        assertEquals("APPLIED", loweredAtm["status"].asText())
        assertEquals("450.00", loweredAtm["limit"]["currentAmount"]["amount"].asText())

        val dailyTransferPath = "/limitguard/demo/api/v1/customer/limits/" + limitsBefore.first {
            it["operation"].asText() == "BANK_TRANSFER" && it["limitKind"].asText() == "PERIODIC"
        }["id"].asText()
        val held = patchJson(
            dailyTransferPath,
            "journey-daily-transfer-001",
            objectMapper.writeValueAsString(mapOf("amount" to "9000.00")),
        )
        assertEquals("REQUESTED", held["status"].asText())
        assertEquals("7500.00", held["limit"]["agreedMaximum"]["amount"].asText())
        assertEquals("5000.00", held["limit"]["currentAmount"]["amount"].asText())
        assertEquals("9000.00", held["limit"]["pendingChange"]["requestedAmount"]["amount"].asText())
        mockMvc.patch(dailyTransferPath) {
            contentType = MediaType.APPLICATION_JSON
            header("Idempotency-Key", "journey-daily-transfer-002")
            content = objectMapper.writeValueAsString(mapOf("amount" to "6000.00"))
        }.andExpect { status { isConflict() } }

        val withdrawn = json(
            mockMvc.delete("$dailyTransferPath/pending-change").andExpect { status { isOk() } }.andReturn(),
        )
        assertEquals("CANCELLED", withdrawn["status"].asText())
        assertTrue(withdrawn["limit"]["pendingChange"].isNull)

        val heldForRejection = patchJson(
            dailyTransferPath,
            "journey-daily-transfer-003",
            objectMapper.writeValueAsString(mapOf("amount" to "8000.00")),
        )
        val rejected = postJson(
            "/limitguard/demo/api/v1/integrations/limit-changes/" +
                heldForRejection["limit"]["pendingChange"]["id"].asText() + "/rejection",
            objectMapper.writeValueAsString(mapOf("reason" to "Not this month")),
        )
        assertEquals("REJECTED", rejected["status"].asText())
        assertEquals("5000.00", rejected["limit"]["currentAmount"]["amount"].asText())

        val heldForApproval = patchJson(
            dailyTransferPath,
            "journey-daily-transfer-004",
            objectMapper.writeValueAsString(mapOf("amount" to "9000.00")),
        )
        val approvalPath = "/limitguard/demo/api/v1/integrations/limit-changes/" +
            heldForApproval["limit"]["pendingChange"]["id"].asText() + "/approval"
        val approved = postJson(approvalPath, "{}")
        assertEquals("APPLIED", approved["status"].asText())
        assertEquals("9000.00", approved["limit"]["currentAmount"]["amount"].asText())
        assertTrue(approved["limit"]["pendingChange"].isNull)
        mockMvc.post(approvalPath).andExpect { status { isNotFound() } }

        val coolingOff = patchJson(
            "/limitguard/demo/api/v1/customer/limits/$cardLimitId",
            "journey-card-limit-raise-001",
            objectMapper.writeValueAsString(mapOf("amount" to "2000.00")),
        )
        assertEquals("PENDING_EFFECTIVE_DATE", coolingOff["status"].asText())
        assertEquals("1500.00", coolingOff["limit"]["currentAmount"]["amount"].asText())
        assertTrue(coolingOff["limit"]["pendingChange"]["effectiveAt"].isTextual)
        Thread.sleep(1_500)
        dueLimitChangeJob.applyDueChanges()
        val raisedCard = getJson("/limitguard/demo/api/v1/customer/limits").first { it["id"].asText() == cardLimitId }
        assertEquals("2000.00", raisedCard["currentAmount"]["amount"].asText())
        assertTrue(raisedCard["pendingChange"].isNull)

        val transferAllowed = postJson(
            "/limitguard/demo/api/v1/integrations/payment-authorizations",
            "journey-transfer-auth-002",
            authorizationBody(
                "journey-transfer-002",
                "BANK_TRANSFER",
                accountId,
                null,
                "2600.00",
                "Apartment deposit retry",
                "TRANSFER",
            ),
        )
        assertEquals("ALLOWED", transferAllowed["decision"].asText())
        postJson(
            "/limitguard/demo/api/v1/integrations/payments/journey-transfer-002/captures",
            "journey-transfer-capture-002",
            objectMapper.writeValueAsString(mapOf("amount" to "2600.00")),
        )
        val afterTransferCapture = getJson("/limitguard/demo/api/v1/customer/accounts/$accountId")
        assertEquals(
            openingBalance - 85.40.toBigDecimal() - 2600.toBigDecimal(),
            afterTransferCapture["bookedBalance"]["amount"].decimal(),
        )
        postJson(
            "/limitguard/demo/api/v1/integrations/payments/journey-transfer-002/reversals",
            "journey-transfer-reversal-002",
            objectMapper.writeValueAsString(mapOf("amount" to "2600.00", "reason" to "Transfer recalled")),
        )
        val afterReversal = getJson("/limitguard/demo/api/v1/customer/accounts/$accountId")
        assertEquals(openingBalance - 85.40.toBigDecimal(), afterReversal["bookedBalance"]["amount"].decimal())
        assertEquals(openingAvailable - 85.40.toBigDecimal(), afterReversal["availableBalance"]["amount"].decimal())

        val repairBody = authorizationBody(
            "journey-repair-001",
            "CARD_PURCHASE",
            accountId,
            cardId,
            "5.00",
            "Repairable hold",
            "OTHER",
        )
        val firstRepairResult = postJson(
            "/limitguard/demo/api/v1/integrations/payment-authorizations",
            "journey-repair-auth-001",
            repairBody,
        )
        jdbcClient.sql(
            """
            UPDATE demo_payment
            SET processing_state = 'FAILED',
                decision = NULL,
                authorization_decision_id = NULL,
                payment_transaction_id = NULL,
                transaction_status = NULL,
                authorized_amount = 0,
                uncaptured_amount = 0
            WHERE external_payment_id = 'journey-repair-001'
            """.trimIndent(),
        ).update()
        val repaired = postJson(
            "/limitguard/demo/api/v1/integrations/payment-authorizations",
            "journey-repair-auth-001",
            repairBody,
        )
        assertEquals(true, repaired["replayed"].asBoolean())
        assertEquals(firstRepairResult["authorizationDecisionId"].asText(), repaired["authorizationDecisionId"].asText())
        assertEquals("COMPLETED", jdbcClient.sql(
            "SELECT processing_state FROM demo_payment WHERE external_payment_id = 'journey-repair-001'",
        ).query(String::class.java).single())

        val existingScenarioId = scenarioService.current().id
        scenarioService.ensureInitialized()
        assertEquals(existingScenarioId, scenarioService.current().id)
        assertEquals(
            1,
            jdbcClient.sql("SELECT count(*) FROM demo_scenario WHERE id = :scenarioId")
                .param("scenarioId", scenarioId)
                .query(Int::class.java)
                .single(),
        )

        mockMvc.post("/limitguard/demo/api/v1/integrations/payment-authorizations") {
            contentType = MediaType.APPLICATION_JSON
            content = repairBody
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("VALIDATION_FAILED") }
            jsonPath("$.message") { value("Request is malformed or incomplete") }
            jsonPath("$.correlationId") { isNotEmpty() }
        }
        mockMvc.get("/limitguard/demo/api/v1/customer/payments/does-not-exist").andExpect {
            status { isNotFound() }
            jsonPath("$.code") { value("NOT_FOUND") }
            jsonPath("$.message") { value("Payment not found") }
        }

        val history = getJson("/limitguard/demo/api/v1/customer/payments/journey-card-001")
        val timeline = history["timeline"]
        assertEquals(
            listOf("AUTHORIZATION", "CAPTURE"),
            (0 until timeline.size()).map { index -> timeline[index]["type"].asText() },
        )
        assertNotEquals(history["payment"]["createdAt"].asText(), "")
        assertTrue(getJson("/limitguard/demo/api/v1/customer/overview")["recentPayments"].size() >= 6)
    }

    @Test
    fun `balances and the operation filter count payments beyond the newest hundred`() {
        val scenarioId = scenarioService.current().id
        val accountId = UUID.fromString(getJson("/limitguard/demo/api/v1/scenario")["accountIds"][0].asText())
        val account = "/limitguard/demo/api/v1/customer/accounts/$accountId"
        val bookedBefore = getJson(account)["bookedBalance"]["amount"].decimal()
        jdbcClient.sql(
            """
            INSERT INTO demo_payment (
                external_payment_id, scenario_id, account_id, operation, description, category,
                requested_amount, currency, authorization_idempotency_key, processing_state, decision,
                captured_amount, created_at, updated_at
            )
            SELECT 'old-payment-' || i, :scenarioId, :accountId,
                   CASE WHEN i = 0 THEN 'ATM_WITHDRAWAL' ELSE 'CARD_PURCHASE' END, 'Old payment', 'OTHER',
                   1, 'EUR', 'old-payment-' || i, 'COMPLETED', 'ALLOWED',
                   1, TIMESTAMPTZ '2000-01-01' + i * INTERVAL '1 second', TIMESTAMPTZ '2000-01-01'
            FROM generate_series(0, 149) AS i
            """.trimIndent(),
        ).param("scenarioId", scenarioId).param("accountId", accountId).update()
        try {
            val booked = getJson(account)["bookedBalance"]["amount"].decimal()
            assertEquals(0, (bookedBefore - 150.toBigDecimal()).compareTo(booked), "booked was $booked")
            val withdrawals = getJson("/limitguard/demo/api/v1/customer/payments?operation=ATM_WITHDRAWAL")
            assertTrue(withdrawals.any { it["externalPaymentId"].asText() == "old-payment-0" })
        } finally {
            jdbcClient.sql("DELETE FROM demo_payment WHERE external_payment_id LIKE 'old-payment-%'").update()
        }
    }

    private fun getJson(path: String): JsonNode =
        json(mockMvc.get(path).andExpect { status { isOk() } }.andReturn())

    private fun postJson(path: String, idempotencyKey: String, body: String): JsonNode =
        json(
            mockMvc.post(path) {
                contentType = MediaType.APPLICATION_JSON
                header("Idempotency-Key", idempotencyKey)
                content = body
            }.andExpect { status { isOk() } }.andReturn(),
        )

    private fun postJson(path: String, body: String): JsonNode =
        json(
            mockMvc.post(path) {
                contentType = MediaType.APPLICATION_JSON
                content = body
            }.andExpect { status { isOk() } }.andReturn(),
        )

    private fun patchJson(path: String, idempotencyKey: String, body: String): JsonNode =
        json(
            mockMvc.patch(path) {
                contentType = MediaType.APPLICATION_JSON
                header("Idempotency-Key", idempotencyKey)
                content = body
            }.andExpect { status { isOk() } }.andReturn(),
        )

    private fun authorizationBody(
        externalPaymentId: String,
        operation: String,
        accountId: UUID,
        cardId: UUID?,
        amount: String,
        description: String,
        category: String,
    ): String = objectMapper.writeValueAsString(
        buildMap<String, Any> {
            put("externalPaymentId", externalPaymentId)
            put("operation", operation)
            put("accountId", accountId)
            cardId?.let { put("cardId", it) }
            put("description", description)
            put("category", category)
            put("amount", amount)
            put("currency", "EUR")
        },
    )

    private fun json(result: MvcResult): JsonNode = objectMapper.readTree(result.response.contentAsString)

    private fun JsonNode.decimal() = asText().toBigDecimal()

    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:18")

        @DynamicPropertySource
        @JvmStatic
        fun databaseProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") {
                "${postgres.jdbcUrl}?currentSchema=limitguard_demo"
            }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }
        }
    }
}
