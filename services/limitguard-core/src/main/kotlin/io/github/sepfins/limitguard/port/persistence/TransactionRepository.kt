package io.github.sepfins.limitguard.port.persistence

import io.github.sepfins.limitguard.domain.common.FundingSourceId
import io.github.sepfins.limitguard.domain.common.PartyProfileId
import io.github.sepfins.limitguard.domain.common.PaymentTransactionId
import io.github.sepfins.limitguard.domain.common.TenantId
import io.github.sepfins.limitguard.domain.transaction.PaymentTransaction
import java.time.Instant

interface TransactionRepository {
    fun findByFundingSourceId(tenantId: TenantId, fundingSourceId: FundingSourceId): List<PaymentTransaction>
    fun findByActingPartyProfileId(tenantId: TenantId, actingPartyProfileId: PartyProfileId): List<PaymentTransaction>
    fun findByActingPartyProfileAndFundingSource(
        tenantId: TenantId,
        actingPartyProfileId: PartyProfileId,
        fundingSourceId: FundingSourceId,
    ): List<PaymentTransaction>
    fun findById(tenantId: TenantId, id: PaymentTransactionId): PaymentTransaction?
    fun findByIdForUpdate(tenantId: TenantId, id: PaymentTransactionId): PaymentTransaction?

    /**
     * Returns the ids of up to [limit] transactions whose uncaptured authorization
     * is due at [at]. Callers lock and recheck each row before applying expiry.
     */
    fun findIdsDueForExpiry(tenantId: TenantId, at: Instant, limit: Int): List<PaymentTransactionId>
    fun insert(tenantId: TenantId, transaction: PaymentTransaction): PaymentTransaction
    fun update(tenantId: TenantId, transaction: PaymentTransaction): PaymentTransaction
}
