package io.github.sepfins.limitguard.demo.persistence

import io.github.sepfins.limitguard.api.AuthorizationDecision
import io.github.sepfins.limitguard.api.TransactionStatus
import io.github.sepfins.limitguard.demo.domain.AccountType
import io.github.sepfins.limitguard.demo.domain.CardStatus
import io.github.sepfins.limitguard.demo.domain.DemoAccount
import io.github.sepfins.limitguard.demo.domain.DemoCard
import io.github.sepfins.limitguard.demo.domain.DemoLimitSetting
import io.github.sepfins.limitguard.demo.domain.DemoOperation
import io.github.sepfins.limitguard.demo.domain.DemoPayment
import io.github.sepfins.limitguard.demo.domain.DemoPaymentMutation
import io.github.sepfins.limitguard.demo.domain.DemoPaymentTotals
import io.github.sepfins.limitguard.demo.domain.DemoScenario
import io.github.sepfins.limitguard.demo.domain.DemoViolation
import io.github.sepfins.limitguard.demo.domain.PaymentCategory
import io.github.sepfins.limitguard.demo.domain.PaymentMutationType
import io.github.sepfins.limitguard.demo.domain.ProcessingState
import io.github.sepfins.limitguard.demo.domain.ScenarioStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.ObjectMapper
import java.math.BigDecimal
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

@Repository
class DemoBankRepository(
    private val jdbcClient: JdbcClient,
    private val objectMapper: ObjectMapper,
) {
    fun findScenarioByKey(scenarioKey: String): DemoScenario? =
        jdbcClient.sql(
            """
            SELECT *
            FROM demo_scenario
            WHERE scenario_key = :scenarioKey
            """.trimIndent(),
        )
            .param("scenarioKey", scenarioKey)
            .query(::mapScenario)
            .optional()
            .orElse(null)

    fun insertBootstrappingScenario(id: UUID, scenarioKey: String, now: Instant) {
        jdbcClient.sql(
            """
            INSERT INTO demo_scenario (
                id, scenario_key, status, created_at, updated_at
            ) VALUES (
                :id, :scenarioKey, 'BOOTSTRAPPING', :now, :now
            )
            """.trimIndent(),
        )
            .param("id", id)
            .param("scenarioKey", scenarioKey)
            .param("now", now.databaseValue())
            .update()
    }

    fun completeScenario(
        id: UUID,
        tenantId: UUID,
        customerPartyProfileId: UUID,
        ownerPartyProfileId: UUID,
        authorityDelegationId: UUID,
        mandateId: UUID,
        now: Instant,
    ) {
        jdbcClient.sql(
            """
            UPDATE demo_scenario
            SET status = 'READY',
                tenant_id = :tenantId,
                customer_party_profile_id = :customerPartyProfileId,
                owner_party_profile_id = :ownerPartyProfileId,
                authority_delegation_id = :authorityDelegationId,
                mandate_id = :mandateId,
                updated_at = :now
            WHERE id = :id
            """.trimIndent(),
        )
            .param("id", id)
            .param("tenantId", tenantId)
            .param("customerPartyProfileId", customerPartyProfileId)
            .param("ownerPartyProfileId", ownerPartyProfileId)
            .param("authorityDelegationId", authorityDelegationId)
            .param("mandateId", mandateId)
            .param("now", now.databaseValue())
            .update()
    }

    fun failScenario(id: UUID, now: Instant) {
        jdbcClient.sql(
            """
            UPDATE demo_scenario
            SET status = 'FAILED', updated_at = :now
            WHERE id = :id
            """.trimIndent(),
        )
            .param("id", id)
            .param("now", now.databaseValue())
            .update()
    }

    fun insertAccount(account: DemoAccount) {
        jdbcClient.sql(
            """
            INSERT INTO demo_account (
                id, scenario_id, funding_source_id, owner_party_profile_id,
                account_type, display_name, masked_iban, access_label,
                currency, opening_balance, display_order, created_at
            ) VALUES (
                :id, :scenarioId, :fundingSourceId, :ownerPartyProfileId,
                :accountType, :displayName, :maskedIban, :accessLabel,
                :currency, :openingBalance, :displayOrder, :createdAt
            )
            """.trimIndent(),
        )
            .param("id", account.id)
            .param("scenarioId", account.scenarioId)
            .param("fundingSourceId", account.fundingSourceId)
            .param("ownerPartyProfileId", account.ownerPartyProfileId)
            .param("accountType", account.accountType.name)
            .param("displayName", account.displayName)
            .param("maskedIban", account.maskedIban)
            .param("accessLabel", account.accessLabel)
            .param("currency", account.currency)
            .param("openingBalance", account.openingBalance)
            .param("displayOrder", account.displayOrder)
            .param("createdAt", account.createdAt.databaseValue())
            .update()
    }

    fun listAccounts(scenarioId: UUID): List<DemoAccount> =
        jdbcClient.sql(
            """
            SELECT *
            FROM demo_account
            WHERE scenario_id = :scenarioId
            ORDER BY display_order, id
            """.trimIndent(),
        )
            .param("scenarioId", scenarioId)
            .query(::mapAccount)
            .list()

    fun findAccount(scenarioId: UUID, accountId: UUID): DemoAccount? =
        jdbcClient.sql(
            """
            SELECT *
            FROM demo_account
            WHERE scenario_id = :scenarioId AND id = :accountId
            """.trimIndent(),
        )
            .param("scenarioId", scenarioId)
            .param("accountId", accountId)
            .query(::mapAccount)
            .optional()
            .orElse(null)

    fun insertCard(card: DemoCard) {
        jdbcClient.sql(
            """
            INSERT INTO demo_card (
                id, scenario_id, account_id, display_name, last_four,
                expires_month, expires_year, status, network, created_at
            ) VALUES (
                :id, :scenarioId, :accountId, :displayName, :lastFour,
                :expiresMonth, :expiresYear, :status, :network, :createdAt
            )
            """.trimIndent(),
        )
            .param("id", card.id)
            .param("scenarioId", card.scenarioId)
            .param("accountId", card.accountId)
            .param("displayName", card.displayName)
            .param("lastFour", card.lastFour)
            .param("expiresMonth", card.expiresMonth)
            .param("expiresYear", card.expiresYear)
            .param("status", card.status.name)
            .param("network", card.network)
            .param("createdAt", card.createdAt.databaseValue())
            .update()
    }

    fun listCards(scenarioId: UUID): List<DemoCard> =
        jdbcClient.sql("SELECT * FROM demo_card WHERE scenario_id = :scenarioId ORDER BY created_at, id")
            .param("scenarioId", scenarioId)
            .query(::mapCard)
            .list()

    fun findCard(scenarioId: UUID, cardId: UUID): DemoCard? =
        jdbcClient.sql("SELECT * FROM demo_card WHERE scenario_id = :scenarioId AND id = :cardId")
            .param("scenarioId", scenarioId)
            .param("cardId", cardId)
            .query(::mapCard)
            .optional()
            .orElse(null)

    fun insertLimitSetting(setting: DemoLimitSetting) {
        jdbcClient.sql(
            """
            INSERT INTO demo_limit_setting (
                id, scenario_id, operation, display_name, limit_rule_id,
                limit_configuration_id, operation_type_id, channel_id,
                minimum_amount, maximum_amount, step_amount, agreed_maximum_amount,
                pending_change_request_id, pending_change_amount, pending_change_effective_at,
                display_order, created_at
            ) VALUES (
                :id, :scenarioId, :operation, :displayName, :limitRuleId,
                :configurationId, :operationTypeId, :channelId,
                :minimumAmount, :maximumAmount, :stepAmount, :agreedMaximumAmount,
                :pendingChangeRequestId, :pendingChangeAmount, :pendingChangeEffectiveAt,
                :displayOrder, :createdAt
            )
            """.trimIndent(),
        )
            .param("id", setting.id)
            .param("scenarioId", setting.scenarioId)
            .param("operation", setting.operation.name)
            .param("displayName", setting.displayName)
            .param("limitRuleId", setting.limitRuleId)
            .param("configurationId", setting.limitConfigurationId)
            .param("operationTypeId", setting.operationTypeId)
            .param("channelId", setting.channelId)
            .param("minimumAmount", setting.minimumAmount)
            .param("maximumAmount", setting.maximumAmount)
            .param("stepAmount", setting.stepAmount)
            .param("agreedMaximumAmount", setting.agreedMaximumAmount)
            .param("pendingChangeRequestId", setting.pendingChangeRequestId)
            .param("pendingChangeAmount", setting.pendingChangeAmount)
            .param("pendingChangeEffectiveAt", setting.pendingChangeEffectiveAt?.databaseValue())
            .param("displayOrder", setting.displayOrder)
            .param("createdAt", setting.createdAt.databaseValue())
            .update()
    }

    fun listLimitSettings(scenarioId: UUID): List<DemoLimitSetting> =
        jdbcClient.sql(
            """
            SELECT *
            FROM demo_limit_setting
            WHERE scenario_id = :scenarioId
            ORDER BY display_order, id
            """.trimIndent(),
        )
            .param("scenarioId", scenarioId)
            .query(::mapLimitSetting)
            .list()

    fun findLimitSetting(scenarioId: UUID, settingId: UUID): DemoLimitSetting? =
        jdbcClient.sql("SELECT * FROM demo_limit_setting WHERE scenario_id = :scenarioId AND id = :settingId")
            .param("scenarioId", scenarioId)
            .param("settingId", settingId)
            .query(::mapLimitSetting)
            .optional()
            .orElse(null)

    fun findLimitSettingByPendingChange(scenarioId: UUID, changeRequestId: UUID): DemoLimitSetting? =
        jdbcClient.sql(
            "SELECT * FROM demo_limit_setting WHERE scenario_id = :scenarioId AND pending_change_request_id = :changeId",
        )
            .param("scenarioId", scenarioId)
            .param("changeId", changeRequestId)
            .query(::mapLimitSetting)
            .optional()
            .orElse(null)

    fun updatePendingChange(settingId: UUID, changeRequestId: UUID?, amount: BigDecimal?, effectiveAt: Instant?) {
        jdbcClient.sql(
            """
            UPDATE demo_limit_setting
            SET pending_change_request_id = :changeId, pending_change_amount = :amount,
                pending_change_effective_at = :effectiveAt
            WHERE id = :settingId
            """.trimIndent(),
        )
            .param("settingId", settingId)
            .param("changeId", changeRequestId)
            .param("amount", amount)
            .param("effectiveAt", effectiveAt?.databaseValue())
            .update()
    }

    fun insertPaymentIntent(payment: DemoPayment): Boolean =
        jdbcClient.sql(
            """
            INSERT INTO demo_payment (
                external_payment_id, scenario_id, account_id, card_id, operation,
                description, category, requested_amount, currency,
                authorization_idempotency_key, processing_state, created_at, updated_at
            ) VALUES (
                :externalPaymentId, :scenarioId, :accountId, :cardId, :operation,
                :description, :category, :requestedAmount, :currency,
                :idempotencyKey, :processingState, :createdAt, :updatedAt
            )
            ON CONFLICT DO NOTHING
            """.trimIndent(),
        )
            .param("externalPaymentId", payment.externalPaymentId)
            .param("scenarioId", payment.scenarioId)
            .param("accountId", payment.accountId)
            .param("cardId", payment.cardId)
            .param("operation", payment.operation.name)
            .param("description", payment.description)
            .param("category", payment.category.name)
            .param("requestedAmount", payment.requestedAmount)
            .param("currency", payment.currency)
            .param("idempotencyKey", payment.authorizationIdempotencyKey)
            .param("processingState", payment.processingState.name)
            .param("createdAt", payment.createdAt.databaseValue())
            .param("updatedAt", payment.updatedAt.databaseValue())
            .update() == 1

    fun completeAuthorization(payment: DemoPayment) {
        jdbcClient.sql(
            """
            UPDATE demo_payment
            SET processing_state = 'COMPLETED',
                decision = :decision,
                authorization_decision_id = :authorizationDecisionId,
                payment_transaction_id = :paymentTransactionId,
                transaction_status = :transactionStatus,
                authorized_amount = :authorizedAmount,
                captured_amount = :capturedAmount,
                uncaptured_amount = :uncapturedAmount,
                violations = CAST(:violations AS jsonb),
                failure_code = NULL,
                updated_at = :updatedAt
            WHERE external_payment_id = :externalPaymentId
            """.trimIndent(),
        )
            .param("externalPaymentId", payment.externalPaymentId)
            .param("decision", payment.decision?.name)
            .param("authorizationDecisionId", payment.authorizationDecisionId)
            .param("paymentTransactionId", payment.paymentTransactionId)
            .param("transactionStatus", payment.transactionStatus?.name)
            .param("authorizedAmount", payment.authorizedAmount)
            .param("capturedAmount", payment.capturedAmount)
            .param("uncapturedAmount", payment.uncapturedAmount)
            .param("violations", objectMapper.writeValueAsString(payment.violations))
            .param("updatedAt", payment.updatedAt.databaseValue())
            .update()
    }

    fun markPaymentFailed(externalPaymentId: String, failureCode: String, now: Instant) {
        jdbcClient.sql(
            """
            UPDATE demo_payment
            SET processing_state = 'FAILED', failure_code = :failureCode, updated_at = :now
            WHERE external_payment_id = :externalPaymentId
            """.trimIndent(),
        )
            .param("externalPaymentId", externalPaymentId)
            .param("failureCode", failureCode)
            .param("now", now.databaseValue())
            .update()
    }

    fun synchronizePayment(
        externalPaymentId: String,
        status: TransactionStatus,
        authorizedAmount: BigDecimal,
        capturedAmount: BigDecimal,
        uncapturedAmount: BigDecimal,
        now: Instant,
    ) {
        jdbcClient.sql(
            """
            UPDATE demo_payment
            SET processing_state = 'COMPLETED',
                transaction_status = :status,
                authorized_amount = :authorizedAmount,
                captured_amount = :capturedAmount,
                uncaptured_amount = :uncapturedAmount,
                failure_code = NULL,
                updated_at = :now
            WHERE external_payment_id = :externalPaymentId
            """.trimIndent(),
        )
            .param("externalPaymentId", externalPaymentId)
            .param("status", status.name)
            .param("authorizedAmount", authorizedAmount)
            .param("capturedAmount", capturedAmount)
            .param("uncapturedAmount", uncapturedAmount)
            .param("now", now.databaseValue())
            .update()
    }

    fun findPayment(externalPaymentId: String): DemoPayment? =
        jdbcClient.sql("SELECT * FROM demo_payment WHERE external_payment_id = :externalPaymentId")
            .param("externalPaymentId", externalPaymentId)
            .query(::mapPayment)
            .optional()
            .orElse(null)

    fun findPaymentByIdempotencyKey(scenarioId: UUID, idempotencyKey: String): DemoPayment? =
        jdbcClient.sql(
            """
            SELECT *
            FROM demo_payment
            WHERE scenario_id = :scenarioId
              AND authorization_idempotency_key = :idempotencyKey
            """.trimIndent(),
        )
            .param("scenarioId", scenarioId)
            .param("idempotencyKey", idempotencyKey)
            .query(::mapPayment)
            .optional()
            .orElse(null)

    fun listPayments(scenarioId: UUID, limit: Int = 100, operation: DemoOperation? = null): List<DemoPayment> =
        jdbcClient.sql(
            """
            SELECT *
            FROM demo_payment
            WHERE scenario_id = :scenarioId AND processing_state <> 'FAILED'
              AND (CAST(:operation AS VARCHAR) IS NULL OR operation = :operation)
            ORDER BY created_at DESC, external_payment_id DESC
            LIMIT :limit
            """.trimIndent(),
        )
            .param("scenarioId", scenarioId)
            .param("operation", operation?.name)
            .param("limit", limit)
            .query(::mapPayment)
            .list()

    /** Totals every allowed payment per account. An account with none has no entry. */
    fun allowedPaymentTotals(scenarioId: UUID): Map<UUID, DemoPaymentTotals> =
        jdbcClient.sql(
            """
            SELECT account_id, SUM(captured_amount) AS captured, SUM(uncaptured_amount) AS uncaptured
            FROM demo_payment
            WHERE scenario_id = :scenarioId AND processing_state <> 'FAILED' AND decision = 'ALLOWED'
            GROUP BY account_id
            """.trimIndent(),
        )
            .param("scenarioId", scenarioId)
            .query { rs, _ ->
                rs.getObject("account_id", UUID::class.java) to
                    DemoPaymentTotals(rs.getBigDecimal("captured"), rs.getBigDecimal("uncaptured"))
            }
            .list()
            .toMap()

    fun insertMutationIntent(mutation: DemoPaymentMutation): Boolean =
        jdbcClient.sql(
            """
            INSERT INTO demo_payment_mutation (
                id, external_payment_id, mutation_type, idempotency_key,
                requested_amount, reason, processing_state, created_at
            ) VALUES (
                :id, :externalPaymentId, :mutationType, :idempotencyKey,
                :requestedAmount, :reason, :processingState, :createdAt
            )
            ON CONFLICT DO NOTHING
            """.trimIndent(),
        )
            .param("id", mutation.id)
            .param("externalPaymentId", mutation.externalPaymentId)
            .param("mutationType", mutation.mutationType.name)
            .param("idempotencyKey", mutation.idempotencyKey)
            .param("requestedAmount", mutation.requestedAmount)
            .param("reason", mutation.reason)
            .param("processingState", mutation.processingState.name)
            .param("createdAt", mutation.createdAt.databaseValue())
            .update() == 1

    fun completeMutation(mutation: DemoPaymentMutation) {
        jdbcClient.sql(
            """
            UPDATE demo_payment_mutation
            SET processing_state = 'COMPLETED',
                result_status = :resultStatus,
                result_amount = :resultAmount,
                transaction_event_id = :transactionEventId,
                authorized_amount = :authorizedAmount,
                captured_amount = :capturedAmount,
                uncaptured_amount = :uncapturedAmount,
                completed_at = :completedAt
            WHERE id = :id
            """.trimIndent(),
        )
            .param("id", mutation.id)
            .param("resultStatus", mutation.resultStatus?.name)
            .param("resultAmount", mutation.resultAmount)
            .param("transactionEventId", mutation.transactionEventId)
            .param("authorizedAmount", mutation.authorizedAmount)
            .param("capturedAmount", mutation.capturedAmount)
            .param("uncapturedAmount", mutation.uncapturedAmount)
            .param("completedAt", mutation.completedAt?.databaseValue())
            .update()
    }

    fun markMutationFailed(id: UUID, now: Instant) {
        jdbcClient.sql(
            """
            UPDATE demo_payment_mutation
            SET processing_state = 'FAILED', completed_at = :now
            WHERE id = :id
            """.trimIndent(),
        )
            .param("id", id)
            .param("now", now.databaseValue())
            .update()
    }

    fun findMutationByIdempotencyKey(idempotencyKey: String): DemoPaymentMutation? =
        jdbcClient.sql("SELECT * FROM demo_payment_mutation WHERE idempotency_key = :idempotencyKey")
            .param("idempotencyKey", idempotencyKey)
            .query(::mapMutation)
            .optional()
            .orElse(null)

    private fun mapScenario(rs: ResultSet, @Suppress("UNUSED_PARAMETER") rowNumber: Int): DemoScenario =
        DemoScenario(
            id = rs.getObject("id", UUID::class.java),
            scenarioKey = rs.getString("scenario_key"),
            status = ScenarioStatus.valueOf(rs.getString("status")),
            tenantId = rs.uuidOrNull("tenant_id"),
            customerPartyProfileId = rs.uuidOrNull("customer_party_profile_id"),
            ownerPartyProfileId = rs.uuidOrNull("owner_party_profile_id"),
            authorityDelegationId = rs.uuidOrNull("authority_delegation_id"),
            mandateId = rs.uuidOrNull("mandate_id"),
            createdAt = rs.instant("created_at"),
            updatedAt = rs.instant("updated_at"),
        )

    private fun mapAccount(rs: ResultSet, @Suppress("UNUSED_PARAMETER") rowNumber: Int): DemoAccount =
        DemoAccount(
            id = rs.getObject("id", UUID::class.java),
            scenarioId = rs.getObject("scenario_id", UUID::class.java),
            fundingSourceId = rs.getObject("funding_source_id", UUID::class.java),
            ownerPartyProfileId = rs.getObject("owner_party_profile_id", UUID::class.java),
            accountType = AccountType.valueOf(rs.getString("account_type")),
            displayName = rs.getString("display_name"),
            maskedIban = rs.getString("masked_iban"),
            accessLabel = rs.getString("access_label"),
            currency = rs.getString("currency"),
            openingBalance = rs.getBigDecimal("opening_balance"),
            displayOrder = rs.getInt("display_order"),
            createdAt = rs.instant("created_at"),
        )

    private fun mapCard(rs: ResultSet, @Suppress("UNUSED_PARAMETER") rowNumber: Int): DemoCard =
        DemoCard(
            id = rs.getObject("id", UUID::class.java),
            scenarioId = rs.getObject("scenario_id", UUID::class.java),
            accountId = rs.getObject("account_id", UUID::class.java),
            displayName = rs.getString("display_name"),
            lastFour = rs.getString("last_four"),
            expiresMonth = rs.getInt("expires_month"),
            expiresYear = rs.getInt("expires_year"),
            status = CardStatus.valueOf(rs.getString("status")),
            network = rs.getString("network"),
            createdAt = rs.instant("created_at"),
        )

    private fun mapLimitSetting(rs: ResultSet, @Suppress("UNUSED_PARAMETER") rowNumber: Int): DemoLimitSetting =
        DemoLimitSetting(
            id = rs.getObject("id", UUID::class.java),
            scenarioId = rs.getObject("scenario_id", UUID::class.java),
            operation = DemoOperation.valueOf(rs.getString("operation")),
            displayName = rs.getString("display_name"),
            limitRuleId = rs.getObject("limit_rule_id", UUID::class.java),
            limitConfigurationId = rs.getObject("limit_configuration_id", UUID::class.java),
            operationTypeId = rs.getObject("operation_type_id", UUID::class.java),
            channelId = rs.getObject("channel_id", UUID::class.java),
            minimumAmount = rs.getBigDecimal("minimum_amount"),
            maximumAmount = rs.getBigDecimal("maximum_amount"),
            stepAmount = rs.getBigDecimal("step_amount"),
            agreedMaximumAmount = rs.getBigDecimal("agreed_maximum_amount"),
            pendingChangeRequestId = rs.uuidOrNull("pending_change_request_id"),
            pendingChangeAmount = rs.getBigDecimal("pending_change_amount"),
            pendingChangeEffectiveAt = rs.instantOrNull("pending_change_effective_at"),
            displayOrder = rs.getInt("display_order"),
            createdAt = rs.instant("created_at"),
        )

    private fun mapPayment(rs: ResultSet, @Suppress("UNUSED_PARAMETER") rowNumber: Int): DemoPayment =
        DemoPayment(
            externalPaymentId = rs.getString("external_payment_id"),
            scenarioId = rs.getObject("scenario_id", UUID::class.java),
            accountId = rs.getObject("account_id", UUID::class.java),
            cardId = rs.uuidOrNull("card_id"),
            operation = DemoOperation.valueOf(rs.getString("operation")),
            description = rs.getString("description"),
            category = PaymentCategory.valueOf(rs.getString("category")),
            requestedAmount = rs.getBigDecimal("requested_amount"),
            currency = rs.getString("currency"),
            authorizationIdempotencyKey = rs.getString("authorization_idempotency_key"),
            processingState = ProcessingState.valueOf(rs.getString("processing_state")),
            decision = rs.getString("decision")?.let(AuthorizationDecision::valueOf),
            authorizationDecisionId = rs.uuidOrNull("authorization_decision_id"),
            paymentTransactionId = rs.uuidOrNull("payment_transaction_id"),
            transactionStatus = rs.getString("transaction_status")?.let(TransactionStatus::valueOf),
            authorizedAmount = rs.getBigDecimal("authorized_amount"),
            capturedAmount = rs.getBigDecimal("captured_amount"),
            uncapturedAmount = rs.getBigDecimal("uncaptured_amount"),
            violations = objectMapper.readValue(
                rs.getString("violations"),
                object : TypeReference<List<DemoViolation>>() {},
            ),
            failureCode = rs.getString("failure_code"),
            createdAt = rs.instant("created_at"),
            updatedAt = rs.instant("updated_at"),
        )

    private fun mapMutation(rs: ResultSet, @Suppress("UNUSED_PARAMETER") rowNumber: Int): DemoPaymentMutation =
        DemoPaymentMutation(
            id = rs.getObject("id", UUID::class.java),
            externalPaymentId = rs.getString("external_payment_id"),
            mutationType = PaymentMutationType.valueOf(rs.getString("mutation_type")),
            idempotencyKey = rs.getString("idempotency_key"),
            requestedAmount = rs.getBigDecimal("requested_amount"),
            reason = rs.getString("reason"),
            processingState = ProcessingState.valueOf(rs.getString("processing_state")),
            resultStatus = rs.getString("result_status")?.let(TransactionStatus::valueOf),
            resultAmount = rs.getBigDecimal("result_amount"),
            transactionEventId = rs.uuidOrNull("transaction_event_id"),
            authorizedAmount = rs.getBigDecimal("authorized_amount"),
            capturedAmount = rs.getBigDecimal("captured_amount"),
            uncapturedAmount = rs.getBigDecimal("uncaptured_amount"),
            createdAt = rs.instant("created_at"),
            completedAt = rs.instantOrNull("completed_at"),
        )

    private fun ResultSet.uuidOrNull(column: String): UUID? = getObject(column) as? UUID

    private fun ResultSet.instant(column: String): Instant =
        getObject(column, OffsetDateTime::class.java).toInstant()

    private fun ResultSet.instantOrNull(column: String): Instant? =
        getObject(column, OffsetDateTime::class.java)?.toInstant()

    private fun Instant.databaseValue(): OffsetDateTime = atOffset(ZoneOffset.UTC)
}
