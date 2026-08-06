package io.github.sepfins.limitguard.domain.transaction

import io.github.sepfins.limitguard.domain.common.IdempotencyKey
import io.github.sepfins.limitguard.domain.common.Metadata
import io.github.sepfins.limitguard.domain.common.Money
import io.github.sepfins.limitguard.domain.common.PaymentTransactionId
import io.github.sepfins.limitguard.domain.common.TenantId
import io.github.sepfins.limitguard.domain.common.TransactionEventId
import java.time.Instant

data class TransactionEvent(
    val id: TransactionEventId,
    val tenantId: TenantId,
    val paymentTransactionId: PaymentTransactionId,
    val eventType: TransactionEventType,
    val amount: Money? = null,
    val reason: TransactionEventReason? = null,
    val idempotencyKey: IdempotencyKey? = null,
    val resultSnapshot: TransactionResultSnapshot? = null,
    val metadata: Metadata = Metadata.EMPTY,
    val createdAt: Instant? = null,
) {
    init {
        require((idempotencyKey == null) == (resultSnapshot == null)) {
            "idempotencyKey and resultSnapshot must either both be present or both be absent"
        }
    }
}
