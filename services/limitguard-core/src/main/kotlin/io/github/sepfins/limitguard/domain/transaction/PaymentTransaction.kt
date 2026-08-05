package io.github.sepfins.limitguard.domain.transaction

import io.github.sepfins.limitguard.domain.common.ChannelId
import io.github.sepfins.limitguard.domain.common.FundingSourceId
import io.github.sepfins.limitguard.domain.common.Metadata
import io.github.sepfins.limitguard.domain.common.Money
import io.github.sepfins.limitguard.domain.common.OperationTypeId
import io.github.sepfins.limitguard.domain.common.PartyProfileId
import io.github.sepfins.limitguard.domain.common.PaymentTransactionId
import io.github.sepfins.limitguard.domain.common.TenantId
import java.time.Instant

data class PaymentTransaction(
    val id: PaymentTransactionId,
    val tenantId: TenantId,
    val actingPartyProfileId: PartyProfileId,
    val fundingSourceId: FundingSourceId,
    val operationTypeId: OperationTypeId,
    val channelId: ChannelId,
    val authorizedAmount: Money,
    val capturedAmount: Money,
    val status: TransactionStatus,
    val expiresAt: Instant? = null,
    val metadata: Metadata = Metadata.EMPTY,
    val createdAt: Instant? = null
) {
    init {
        require(authorizedAmount.isPositive) {
            "authorizedAmount must be positive"
        }
        require(capturedAmount.currency == authorizedAmount.currency) {
            "capturedAmount currency must match authorizedAmount currency"
        }
        require(capturedAmount.amount <= authorizedAmount.amount) {
            "capturedAmount must be less than or equal to authorizedAmount"
        }
    }

    fun canTransitionTo(nextStatus: TransactionStatus): Boolean =
        nextStatus in allowedTransitions.getValue(status)

    fun transitionTo(nextStatus: TransactionStatus): PaymentTransaction {
        require(canTransitionTo(nextStatus)) {
            "Cannot transition transaction from $status to $nextStatus"
        }
        return copy(status = nextStatus)
    }

    fun capture(amount: Money): PaymentTransaction {
        require(status == TransactionStatus.AUTHORIZED || status == TransactionStatus.PARTIALLY_CAPTURED) {
            "Only authorized or partially captured transactions can be captured"
        }
        require(amount.isPositive) { "capture amount must be positive" }
        require(amount.currency == authorizedAmount.currency) {
            "capture amount currency must match authorized amount currency"
        }

        val newCapturedAmount = capturedAmount + amount
        require(newCapturedAmount <= authorizedAmount) {
            "capturedAmount must be less than or equal to authorizedAmount"
        }
        val nextStatus =
            if (newCapturedAmount == authorizedAmount) {
                TransactionStatus.CAPTURED
            } else {
                TransactionStatus.PARTIALLY_CAPTURED
            }
        return transitionTo(nextStatus).copy(capturedAmount = newCapturedAmount)
    }

    fun void(): PaymentTransaction =
        transitionTo(TransactionStatus.VOIDED)

    fun reverse(): PaymentTransaction =
        transitionTo(TransactionStatus.REVERSED)

    fun expire(): PaymentTransaction =
        transitionTo(TransactionStatus.EXPIRED)

    companion object {
        private val allowedTransitions: Map<TransactionStatus, Set<TransactionStatus>> =
            mapOf(
                TransactionStatus.AUTHORIZED to setOf(
                    TransactionStatus.CAPTURED,
                    TransactionStatus.PARTIALLY_CAPTURED,
                    TransactionStatus.VOIDED,
                    TransactionStatus.REVERSED,
                    TransactionStatus.EXPIRED,
                ),
                TransactionStatus.PARTIALLY_CAPTURED to setOf(
                    TransactionStatus.PARTIALLY_CAPTURED,
                    TransactionStatus.CAPTURED,
                    TransactionStatus.REVERSED,
                ),
                TransactionStatus.CAPTURED to setOf(TransactionStatus.REVERSED),
                TransactionStatus.VOIDED to emptySet(),
                TransactionStatus.REVERSED to emptySet(),
                TransactionStatus.EXPIRED to emptySet(),
            )
    }
}
