package io.github.sepfins.limitguard.domain.transaction

import io.github.sepfins.limitguard.domain.common.AuthorizationDecisionId
import io.github.sepfins.limitguard.domain.common.ChannelId
import io.github.sepfins.limitguard.domain.common.FundingSourceId
import io.github.sepfins.limitguard.domain.common.IdempotencyKey
import io.github.sepfins.limitguard.domain.common.Metadata
import io.github.sepfins.limitguard.domain.common.Money
import io.github.sepfins.limitguard.domain.common.OperationTypeId
import io.github.sepfins.limitguard.domain.common.PartyProfileId
import io.github.sepfins.limitguard.domain.common.PaymentTransactionId
import io.github.sepfins.limitguard.domain.common.TenantId
import io.github.sepfins.limitguard.domain.common.TransactionEventId
import java.time.Instant

data class AuthorizationDecisionRecord(
    val id: AuthorizationDecisionId,
    val tenantId: TenantId,
    val actingPartyProfileId: PartyProfileId,
    val fundingSourceId: FundingSourceId,
    val operationTypeId: OperationTypeId,
    val channelId: ChannelId,
    val requestedAmount: Money,
    val decision: AuthorizationDecisionOutcome,
    val paymentTransactionId: PaymentTransactionId?,
    val transactionEventId: TransactionEventId?,
    val idempotencyKey: IdempotencyKey?,
    val resultSnapshot: TransactionResultSnapshot,
    val expiresAt: Instant?,
    val metadata: Metadata = Metadata.EMPTY,
    val createdAt: Instant? = null,
) {
    init {
        require(requestedAmount.isPositive) {
            "Authorization decision requestedAmount must be positive"
        }
        if (decision == AuthorizationDecisionOutcome.ALLOWED) {
            require(paymentTransactionId != null && transactionEventId != null) {
                "Allowed decisions must link a transaction and event"
            }
        } else {
            require(paymentTransactionId == null && transactionEventId == null) {
                "Denied and not-applicable decisions must not link a transaction or event"
            }
        }
    }
}
