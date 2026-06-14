package io.github.sepfins.limitguard.domain.transaction

import io.github.sepfins.limitguard.domain.common.ChannelId
import io.github.sepfins.limitguard.domain.common.FundingSourceId
import io.github.sepfins.limitguard.domain.common.IdempotencyKey
import io.github.sepfins.limitguard.domain.common.Metadata
import io.github.sepfins.limitguard.domain.common.Money
import io.github.sepfins.limitguard.domain.common.OperationTypeId
import io.github.sepfins.limitguard.domain.common.PartyProfileId
import io.github.sepfins.limitguard.domain.common.PaymentTransactionId
import io.github.sepfins.limitguard.domain.common.TenantId
import kotlin.time.Instant

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
    val idempotencyKey: IdempotencyKey? = null,
    val expiresAt: Instant? = null,
    val metadata: Metadata = Metadata.EMPTY,
    val createdAt: Instant? = null
) {
    init {
        require(capturedAmount.currency == authorizedAmount.currency) {
            "capturedAmount currency must match authorizedAmount currency"
        }
        require(capturedAmount.amount <= authorizedAmount.amount) {
            "capturedAmount must be less than or equal to authorizedAmount"
        }
    }
}
