package io.github.sepfins.limitguard.domain.common

import java.util.UUID

@JvmInline
value class PaymentTransactionId(val id: UUID) {
    companion object {
        fun of(id: UUID): PaymentTransactionId = PaymentTransactionId(id)
    }
}
