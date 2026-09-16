package io.github.sepfins.limitguard.demo.web.dto

import io.github.sepfins.limitguard.api.AuthorizationDecision
import io.github.sepfins.limitguard.api.LimitKind
import io.github.sepfins.limitguard.api.LimitPeriod
import io.github.sepfins.limitguard.api.LimitRuleChangeStatus
import io.github.sepfins.limitguard.api.OperationCapacityStatus
import io.github.sepfins.limitguard.api.TransactionStatus
import io.github.sepfins.limitguard.demo.domain.AccountType
import io.github.sepfins.limitguard.demo.domain.CardStatus
import io.github.sepfins.limitguard.demo.domain.DemoOperation
import io.github.sepfins.limitguard.demo.domain.PaymentCategory
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.time.Instant
import java.util.UUID

data class MoneyDto(
    val amount: String,
    val currency: String,
)

data class CustomerDto(
    val displayName: String,
    val initials: String,
    val relationship: String,
)

data class ScenarioResponse(
    val scenarioKey: String,
    val customer: CustomerDto,
    val accountIds: List<UUID>,
    val cardIds: List<UUID>,
)

data class AccountResponse(
    val id: UUID,
    val accountType: AccountType,
    val displayName: String,
    val maskedIban: String,
    val accessLabel: String,
    val bookedBalance: MoneyDto,
    val availableBalance: MoneyDto,
)

data class CardResponse(
    val id: UUID,
    val accountId: UUID,
    val displayName: String,
    val lastFour: String,
    val expiresMonth: Int,
    val expiresYear: Int,
    val status: CardStatus,
    val network: String,
)

data class PaymentSummaryResponse(
    val externalPaymentId: String,
    val accountId: UUID,
    val cardId: UUID?,
    val operation: DemoOperation,
    val description: String,
    val category: PaymentCategory,
    val amount: MoneyDto,
    val decision: AuthorizationDecision?,
    val displayStatus: String,
    val transactionStatus: TransactionStatus?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class PaymentViolationResponse(
    val code: String,
    val message: String,
    val limitRuleId: UUID?,
    val limitAmount: MoneyDto?,
    val attemptedAmount: MoneyDto,
    val remainingAmount: MoneyDto?,
)

data class PaymentTimelineItemResponse(
    val type: String,
    val label: String,
    val amount: MoneyDto?,
    val occurredAt: Instant,
)

data class PaymentDetailResponse(
    val payment: PaymentSummaryResponse,
    val authorizedAmount: MoneyDto,
    val capturedAmount: MoneyDto,
    val uncapturedAmount: MoneyDto,
    val violations: List<PaymentViolationResponse>,
    val timeline: List<PaymentTimelineItemResponse>,
)

data class LimitResponse(
    val id: UUID,
    val operation: DemoOperation,
    val displayName: String,
    val limitKind: LimitKind,
    val period: LimitPeriod?,
    val currentAmount: MoneyDto,
    val minimumAmount: MoneyDto,
    val maximumAmount: MoneyDto,
    val stepAmount: MoneyDto,
    val consumedAmount: MoneyDto,
    val reservedAmount: MoneyDto,
    val availableAmount: MoneyDto,
    val currentExposure: MoneyDto,
    val capacityStatus: OperationCapacityStatus,
    val resetsAt: Instant?,
    val version: Int,
    val agreedMaximum: MoneyDto?,
    val pendingChange: PendingLimitChangeResponse?,
)

/** A change the customer asked for that has not applied yet. */
data class PendingLimitChangeResponse(
    val id: UUID,
    val requestedAmount: MoneyDto,
    /** When it starts after a cooling-off period; null while it waits for the account holder. */
    val effectiveAt: Instant?,
)

data class OverviewResponse(
    val customer: CustomerDto,
    val totalBookedBalance: MoneyDto,
    val totalAvailableBalance: MoneyDto,
    val accounts: List<AccountResponse>,
    val primaryCard: CardResponse,
    val recentPayments: List<PaymentSummaryResponse>,
    val limits: List<LimitResponse>,
    val refreshedAt: Instant,
)

data class LimitUpdateRequest(
    @field:NotBlank
    @field:Pattern(regexp = "^[0-9]+(?:\\.[0-9]{1,2})?$")
    val amount: String,
)

data class LimitUpdateResponse(
    val status: LimitRuleChangeStatus,
    val message: String?,
    val limit: LimitResponse,
)

data class LimitChangeRejectionRequest(
    @field:NotBlank
    @field:Size(max = 160)
    val reason: String,
)

data class PaymentAuthorizationRequest(
    @field:NotBlank
    @field:Size(max = 100)
    val externalPaymentId: String,
    @field:NotNull
    val operation: DemoOperation,
    @field:NotNull
    val accountId: UUID,
    val cardId: UUID? = null,
    @field:NotBlank
    @field:Size(max = 160)
    val description: String,
    @field:NotNull
    val category: PaymentCategory,
    @field:NotBlank
    @field:Pattern(regexp = "^[0-9]+(?:\\.[0-9]{1,2})?$")
    val amount: String,
    @field:NotBlank
    @field:Pattern(regexp = "^[A-Z]{3}$")
    val currency: String,
)

data class PaymentAuthorizationResponse(
    val externalPaymentId: String,
    val decision: AuthorizationDecision,
    val authorizationDecisionId: UUID,
    val paymentTransactionId: UUID?,
    val transactionStatus: TransactionStatus?,
    val amount: MoneyDto,
    val violations: List<PaymentViolationResponse>,
    val replayed: Boolean,
)

data class PaymentMutationRequest(
    @field:Pattern(regexp = "^[0-9]+(?:\\.[0-9]{1,2})?$")
    val amount: String? = null,
    @field:Size(max = 160)
    val reason: String? = null,
)

data class PaymentMutationResponse(
    val externalPaymentId: String,
    val transactionStatus: TransactionStatus,
    val mutationAmount: MoneyDto,
    val authorizedAmount: MoneyDto,
    val capturedAmount: MoneyDto,
    val uncapturedAmount: MoneyDto,
    val transactionEventId: UUID?,
    val replayed: Boolean,
)
