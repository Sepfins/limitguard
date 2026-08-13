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
        require(status in ZERO_AUTHORIZATION_STATUSES || authorizedAmount.isPositive) {
            "authorizedAmount must be positive unless the transaction is terminal"
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

    fun release(amount: Money): PaymentTransaction {
        require(status == TransactionStatus.AUTHORIZED || status == TransactionStatus.PARTIALLY_CAPTURED) {
            "Only authorized or partially captured transactions can be released"
        }
        require(amount.isPositive) { "release amount must be positive" }
        require(amount.currency == authorizedAmount.currency) {
            "release amount currency must match authorized amount currency"
        }

        val releasableAmount = authorizedAmount - capturedAmount

        require(amount <= releasableAmount) {
            "release amount must be less than or equal to uncaptured authorized amount"
        }

        val newAuthorizedAmount = authorizedAmount - amount

        val nextStatus =
            when {
                newAuthorizedAmount == capturedAmount && capturedAmount.isZero ->
                    TransactionStatus.VOIDED

                newAuthorizedAmount == capturedAmount ->
                    TransactionStatus.CAPTURED

                capturedAmount.isZero ->
                    TransactionStatus.AUTHORIZED

                else ->
                    TransactionStatus.PARTIALLY_CAPTURED
            }

        return transitionTo(nextStatus).copy(
            authorizedAmount = newAuthorizedAmount,
        )
    }

    fun void(): PaymentTransaction =
        transitionTo(TransactionStatus.VOIDED).copy(
            authorizedAmount = Money.zero(authorizedAmount.currency),
            capturedAmount = Money.zero(capturedAmount.currency),
        )

    fun reverse(amount: Money): PaymentTransaction {
        require(status == TransactionStatus.CAPTURED) {
            "Only captured transactions can be reversed"
        }
        require(amount.isPositive) { "reverse amount must be positive" }
        require(amount.currency == authorizedAmount.currency) {
            "reverse amount currency must match authorized amount currency"
        }
        require(amount <= capturedAmount) {
            "reverse amount must be less than or equal to captured amount"
        }

        val newCapturedAmount = capturedAmount - amount
        val nextStatus =
            if (newCapturedAmount.isZero) {
                TransactionStatus.REVERSED
            } else {
                TransactionStatus.CAPTURED
            }

        return transitionTo(nextStatus).copy(
            authorizedAmount = newCapturedAmount,
            capturedAmount = newCapturedAmount,
        )
    }

    fun reverse(): PaymentTransaction =
        reverse(capturedAmount)

    fun expire(): PaymentTransaction =
        transitionTo(TransactionStatus.EXPIRED)

    companion object {
        private val allowedTransitions: Map<TransactionStatus, Set<TransactionStatus>> =
            mapOf(
                TransactionStatus.AUTHORIZED to setOf(
                    TransactionStatus.AUTHORIZED,
                    TransactionStatus.CAPTURED,
                    TransactionStatus.PARTIALLY_CAPTURED,
                    TransactionStatus.VOIDED,
                    TransactionStatus.EXPIRED,
                ),
                TransactionStatus.PARTIALLY_CAPTURED to setOf(
                    TransactionStatus.PARTIALLY_CAPTURED,
                    TransactionStatus.CAPTURED,
                ),
                TransactionStatus.CAPTURED to setOf(
                    TransactionStatus.CAPTURED,
                    TransactionStatus.REVERSED,
                ),
                TransactionStatus.VOIDED to emptySet(),
                TransactionStatus.REVERSED to emptySet(),
                TransactionStatus.EXPIRED to emptySet(),
            )

        private val ZERO_AUTHORIZATION_STATUSES: Set<TransactionStatus> =
            setOf(
                TransactionStatus.VOIDED,
                TransactionStatus.REVERSED,
                TransactionStatus.EXPIRED,
            )
    }
}
