package io.github.sepfins.limitguard.port.persistence

import io.github.sepfins.limitguard.domain.common.IdempotencyKey
import io.github.sepfins.limitguard.domain.common.PaymentTransactionId
import io.github.sepfins.limitguard.domain.common.TenantId
import io.github.sepfins.limitguard.domain.common.TransactionEventId
import io.github.sepfins.limitguard.domain.transaction.TransactionEvent

interface TransactionEventRepository {
    fun findById(tenantId: TenantId, id: TransactionEventId): TransactionEvent?
    fun findByIdempotencyKey(tenantId: TenantId, key: IdempotencyKey): TransactionEvent?
    fun findByTransaction(tenantId: TenantId, transactionId: PaymentTransactionId): List<TransactionEvent>
    fun insert(tenantId: TenantId, event: TransactionEvent): TransactionEvent
}
