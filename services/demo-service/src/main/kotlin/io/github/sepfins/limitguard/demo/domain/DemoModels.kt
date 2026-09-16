package io.github.sepfins.limitguard.demo.domain

import io.github.sepfins.limitguard.api.AuthorizationDecision
import io.github.sepfins.limitguard.api.TransactionStatus
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

enum class ScenarioStatus {
    BOOTSTRAPPING,
    READY,
    FAILED,
}

enum class AccountType {
    CURRENT,
    SAVINGS,
}

enum class CardStatus {
    ACTIVE,
    INACTIVE,
}

enum class DemoOperation(
    val operationCode: String,
    val channelCode: String,
    val requiresCard: Boolean,
) {
    CARD_PURCHASE("CARD_PURCHASE", "CARD_POS", true),
    ATM_WITHDRAWAL("CASH_WITHDRAWAL", "ATM", true),
    BANK_TRANSFER("BANK_TRANSFER", "ONLINE_BANKING", false),
}

enum class PaymentCategory {
    GROCERIES,
    DINING,
    TRANSPORT,
    CASH,
    TRANSFER,
    SHOPPING,
    OTHER,
}

enum class ProcessingState {
    PROCESSING,
    COMPLETED,
    FAILED,
}

enum class PaymentMutationType {
    CAPTURE,
    RELEASE,
    REVERSAL,
}

data class DemoScenario(
    val id: UUID,
    val scenarioKey: String,
    val status: ScenarioStatus,
    val tenantId: UUID?,
    val customerPartyProfileId: UUID?,
    val ownerPartyProfileId: UUID?,
    val authorityDelegationId: UUID?,
    val mandateId: UUID?,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    fun requireReady(): DemoScenario {
        check(status == ScenarioStatus.READY) { "demo scenario is not ready" }
        checkNotNull(tenantId) { "demo scenario tenant is missing" }
        checkNotNull(customerPartyProfileId) { "demo scenario customer is missing" }
        checkNotNull(ownerPartyProfileId) { "demo scenario owner is missing" }
        checkNotNull(authorityDelegationId) { "demo scenario delegation is missing" }
        checkNotNull(mandateId) { "demo scenario mandate is missing" }
        return this
    }
}

data class DemoAccount(
    val id: UUID,
    val scenarioId: UUID,
    val fundingSourceId: UUID,
    val ownerPartyProfileId: UUID,
    val accountType: AccountType,
    val displayName: String,
    val maskedIban: String,
    val accessLabel: String,
    val currency: String,
    val openingBalance: BigDecimal,
    val displayOrder: Int,
    val createdAt: Instant,
)

data class DemoCard(
    val id: UUID,
    val scenarioId: UUID,
    val accountId: UUID,
    val displayName: String,
    val lastFour: String,
    val expiresMonth: Int,
    val expiresYear: Int,
    val status: CardStatus,
    val network: String,
    val createdAt: Instant,
)

data class DemoLimitSetting(
    val id: UUID,
    val scenarioId: UUID,
    val operation: DemoOperation,
    val displayName: String,
    val limitRuleId: UUID,
    val limitConfigurationId: UUID,
    val operationTypeId: UUID,
    val channelId: UUID,
    val minimumAmount: BigDecimal,
    val maximumAmount: BigDecimal,
    val stepAmount: BigDecimal,
    /** Above this amount the account holder approves the change; null when no approval is agreed. */
    val agreedMaximumAmount: BigDecimal?,
    /**
     * A change that has not applied yet and the amount it asks for. It starts at its effective
     * time after a cooling-off period, or waits for the account holder when that is null.
     */
    val pendingChangeRequestId: UUID?,
    val pendingChangeAmount: BigDecimal?,
    val pendingChangeEffectiveAt: Instant?,
    val displayOrder: Int,
    val createdAt: Instant,
)

data class DemoViolation(
    val code: String,
    val message: String,
    val limitRuleId: UUID?,
    val limitAmount: BigDecimal?,
    val attemptedAmount: BigDecimal,
    val remainingAmount: BigDecimal?,
)

data class DemoPaymentTotals(
    val captured: BigDecimal,
    val uncaptured: BigDecimal,
)

data class DemoPayment(
    val externalPaymentId: String,
    val scenarioId: UUID,
    val accountId: UUID,
    val cardId: UUID?,
    val operation: DemoOperation,
    val description: String,
    val category: PaymentCategory,
    val requestedAmount: BigDecimal,
    val currency: String,
    val authorizationIdempotencyKey: String,
    val processingState: ProcessingState,
    val decision: AuthorizationDecision?,
    val authorizationDecisionId: UUID?,
    val paymentTransactionId: UUID?,
    val transactionStatus: TransactionStatus?,
    val authorizedAmount: BigDecimal,
    val capturedAmount: BigDecimal,
    val uncapturedAmount: BigDecimal,
    val violations: List<DemoViolation>,
    val failureCode: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class DemoPaymentMutation(
    val id: UUID,
    val externalPaymentId: String,
    val mutationType: PaymentMutationType,
    val idempotencyKey: String,
    val requestedAmount: BigDecimal?,
    val reason: String?,
    val processingState: ProcessingState,
    val resultStatus: TransactionStatus?,
    val resultAmount: BigDecimal?,
    val transactionEventId: UUID?,
    val authorizedAmount: BigDecimal?,
    val capturedAmount: BigDecimal?,
    val uncapturedAmount: BigDecimal?,
    val createdAt: Instant,
    val completedAt: Instant?,
)
