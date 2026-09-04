package io.github.sepfins.limitguard.application.evaluation

import io.github.sepfins.limitguard.api.results.AuthorizationResult
import io.github.sepfins.limitguard.application.transaction.TransactionIdempotency
import io.github.sepfins.limitguard.domain.common.AuthorizationDecisionId
import io.github.sepfins.limitguard.domain.common.IdempotencyKey
import io.github.sepfins.limitguard.domain.common.Metadata
import io.github.sepfins.limitguard.domain.common.TenantId
import io.github.sepfins.limitguard.domain.common.TransactionEventId
import io.github.sepfins.limitguard.domain.transaction.AuthorizationDecisionRecord
import io.github.sepfins.limitguard.domain.transaction.PaymentTransaction
import io.github.sepfins.limitguard.port.ids.IdGenerator
import io.github.sepfins.limitguard.port.persistence.AuthorizationDecisionRepository
import java.time.Instant

internal class AuthorizationDecisionRecorder(
    private val repository: AuthorizationDecisionRepository,
    private val idGenerator: IdGenerator,
) {
    fun findByIdempotencyKey(
        tenantId: TenantId,
        idempotencyKey: IdempotencyKey,
    ): AuthorizationDecisionRecord? =
        repository.findByIdempotencyKey(tenantId, idempotencyKey)

    fun record(
        requestedTransaction: PaymentTransaction,
        decision: LimitDecision,
        authorities: List<ResolvedFundingAuthority>,
        paymentTransaction: PaymentTransaction? = null,
        transactionEventId: TransactionEventId? = null,
        idempotencyKey: IdempotencyKey?,
        recordedAt: Instant,
    ): AuthorizationResult {
        val decisionId = AuthorizationDecisionId.of(idGenerator.newId())
        val result = (paymentTransaction ?: requestedTransaction).toAuthorizationResult(
            authorizationDecisionId = decisionId,
            decision = decision,
            transactionEventId = transactionEventId,
        )
        repository.insert(
            requestedTransaction.tenantId,
            AuthorizationDecisionRecord(
                id = decisionId,
                tenantId = requestedTransaction.tenantId,
                actingPartyProfileId = requestedTransaction.actingPartyProfileId,
                fundingSourceId = requestedTransaction.fundingSourceId,
                operationTypeId = requestedTransaction.operationTypeId,
                channelId = requestedTransaction.channelId,
                requestedAmount = requestedTransaction.authorizedAmount,
                decision = decision.outcome,
                paymentTransactionId = paymentTransaction?.id,
                transactionEventId = transactionEventId,
                idempotencyKey = idempotencyKey,
                resultSnapshot = TransactionIdempotency.authorizationResultSnapshot(result),
                expiresAt = requestedTransaction.expiresAt,
                metadata = authorizationMetadata(authorities),
                createdAt = recordedAt,
            ),
        )
        return result
    }

    private fun authorizationMetadata(
        authorities: List<ResolvedFundingAuthority>,
    ): Metadata =
        Metadata(
            mapOf(
                "fundingAuthorities" to authorities.map { authority ->
                    mapOf(
                        "ownerPartyProfileId" to authority.ownerPartyProfileId.value.toString(),
                        "directOwnership" to authority.directOwnership,
                        "mandateIds" to authority.mandateIds
                            .map { it.value.toString() }
                            .sorted(),
                    )
                },
            ),
        )
}
