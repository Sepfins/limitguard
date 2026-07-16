package io.github.sepfins.limitguard.domain.transaction

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
    val metadata: Metadata = Metadata.EMPTY,
    val createdAt: Instant? = null,
)
