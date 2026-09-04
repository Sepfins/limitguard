package io.github.sepfins.limitguard.port.persistence

import io.github.sepfins.limitguard.domain.common.AuthorizationDecisionId
import io.github.sepfins.limitguard.domain.common.IdempotencyKey
import io.github.sepfins.limitguard.domain.common.PaymentTransactionId
import io.github.sepfins.limitguard.domain.common.TenantId
import io.github.sepfins.limitguard.domain.transaction.AuthorizationDecisionRecord

interface AuthorizationDecisionRepository {
    fun findById(tenantId: TenantId, id: AuthorizationDecisionId): AuthorizationDecisionRecord?
    fun findByIdempotencyKey(tenantId: TenantId, key: IdempotencyKey): AuthorizationDecisionRecord?
    fun findByTransaction(
        tenantId: TenantId,
        transactionId: PaymentTransactionId,
    ): AuthorizationDecisionRecord?
    fun insert(tenantId: TenantId, decision: AuthorizationDecisionRecord): AuthorizationDecisionRecord
}
