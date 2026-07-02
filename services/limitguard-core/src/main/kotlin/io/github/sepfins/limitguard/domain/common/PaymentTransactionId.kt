package io.github.sepfins.limitguard.domain.common

import java.util.UUID

@JvmInline
value class PaymentTransactionId(val value: UUID) {
    companion object {
        fun of(value: UUID): PaymentTransactionId = PaymentTransactionId(value)

        fun of(value: String): PaymentTransactionId = of(UUID.fromString(value))
    }
}
