package io.github.sepfins.limitguard.application.transaction

import io.github.sepfins.limitguard.domain.common.Money
import io.github.sepfins.limitguard.domain.transaction.PaymentTransaction
import io.github.sepfins.limitguard.domain.transaction.TransactionEventType
import java.math.BigDecimal

enum class TransactionMutationKind {
    CAPTURE {
        override val operationName: String = "capture"
        override val transactionEventTypes: Set<TransactionEventType> =
            setOf(TransactionEventType.CAPTURE, TransactionEventType.PARTIAL_CAPTURE)

        override fun defaultAmount(transaction: PaymentTransaction): Money =
            transaction.authorizedAmount - transaction.capturedAmount

        override fun apply(transaction: PaymentTransaction, amount: Money): PaymentTransaction =
            transaction.capture(amount)
    },
    RELEASE {
        override val operationName: String = "release"
        override val transactionEventTypes: Set<TransactionEventType> =
            setOf(TransactionEventType.VOID, TransactionEventType.ADJUSTMENT)

        override fun defaultAmount(transaction: PaymentTransaction): Money =
            transaction.authorizedAmount - transaction.capturedAmount

        override fun apply(transaction: PaymentTransaction, amount: Money): PaymentTransaction =
            transaction.release(amount)
    },
    REVERSAL {
        override val operationName: String = "reversal"
        override val transactionEventTypes: Set<TransactionEventType> =
            setOf(TransactionEventType.REVERSAL)

        override fun defaultAmount(transaction: PaymentTransaction): Money =
            transaction.capturedAmount

        override fun apply(transaction: PaymentTransaction, amount: Money): PaymentTransaction =
            transaction.reverse(amount)
    };

    abstract val operationName: String
    abstract val transactionEventTypes: Set<TransactionEventType>
    abstract fun defaultAmount(transaction: PaymentTransaction): Money
    abstract fun apply(transaction: PaymentTransaction, amount: Money): PaymentTransaction

    fun amount(transaction: PaymentTransaction, requestedAmount: BigDecimal?): Money =
        requestedAmount
            ?.let { Money.positive(it, transaction.authorizedAmount.currency) }
            ?: defaultAmount(transaction).also { amount ->
                require(amount.isPositive) {
                    "No remaining amount is available for $operationName"
                }
            }
}
