package io.github.sepfins.limitguard.port.persistence

import io.github.sepfins.limitguard.domain.common.FundingSourceId
import io.github.sepfins.limitguard.domain.common.IdempotencyKey
import io.github.sepfins.limitguard.domain.common.PartyProfileId
import io.github.sepfins.limitguard.domain.common.PaymentTransactionId
import io.github.sepfins.limitguard.domain.common.TenantId
import io.github.sepfins.limitguard.domain.transaction.PaymentTransaction

interface TransactionRepository {
    fun findByFundingSourceId(tenantId: TenantId, fundingSourceId: FundingSourceId): List<PaymentTransaction>
    fun findByActingPartyProfileId(tenantId: TenantId, actingPartyProfileId: PartyProfileId): List<PaymentTransaction>
    fun findByActingPartyProfileAndFundingSource(
        tenantId: TenantId,
        actingPartyProfileId: PartyProfileId,
        fundingSourceId: FundingSourceId,
    ): List<PaymentTransaction>
    fun findById(tenantId: TenantId, id: PaymentTransactionId): PaymentTransaction?
    fun findByIdempotencyKey(tenantId: TenantId, key: IdempotencyKey): PaymentTransaction?
    fun insert(tenantId: TenantId, transaction: PaymentTransaction): PaymentTransaction
    fun update(tenantId: TenantId, transaction: PaymentTransaction): PaymentTransaction
}
