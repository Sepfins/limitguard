package io.github.sepfins.limitguard.application.transaction

import io.github.sepfins.limitguard.application.evaluation.ResolvedFundingAuthority
import io.github.sepfins.limitguard.domain.common.IdempotencyKey
import io.github.sepfins.limitguard.domain.common.Metadata
import io.github.sepfins.limitguard.domain.common.Money
import io.github.sepfins.limitguard.domain.common.TransactionEventId
import io.github.sepfins.limitguard.domain.limit.MatchedLimitRule
import io.github.sepfins.limitguard.domain.transaction.PaymentTransaction
import io.github.sepfins.limitguard.domain.transaction.TransactionEvent
import io.github.sepfins.limitguard.domain.transaction.TransactionEventReason
import io.github.sepfins.limitguard.domain.transaction.TransactionEventType
import io.github.sepfins.limitguard.domain.transaction.TransactionResultSnapshot
import io.github.sepfins.limitguard.domain.transaction.TransactionStatus
import io.github.sepfins.limitguard.port.ids.IdGenerator
import io.github.sepfins.limitguard.port.persistence.TransactionEventRepository
import io.github.sepfins.limitguard.port.persistence.TransactionRepository
import java.time.Instant

internal data class RecordedAuthorization(
    val transaction: PaymentTransaction,
    val event: TransactionEvent,
)

internal data class RecordedMutation(
    val transaction: PaymentTransaction,
    val event: TransactionEvent,
)

internal class TransactionRecorder(
    private val transactionRepository: TransactionRepository,
    private val transactionEventRepository: TransactionEventRepository,
    private val idGenerator: IdGenerator,
) {
    fun recordAuthorization(
        transaction: PaymentTransaction,
        matchedRules: List<MatchedLimitRule>,
        authorities: List<ResolvedFundingAuthority>,
        idempotencyKey: IdempotencyKey?,
        recordedAt: Instant,
    ): RecordedAuthorization {
        val persistedTransaction = transactionRepository.insert(transaction.tenantId, transaction)
        val eventId = TransactionEventId.of(idGenerator.newId())
        val resultSnapshot = idempotencyKey?.let {
            TransactionIdempotency.authorizationResultSnapshot(
                transaction = persistedTransaction,
                transactionEventId = eventId,
                matchedRules = matchedRules,
            )
        }
        val event = transactionEventRepository.insert(
            transaction.tenantId,
            TransactionEvent(
                id = eventId,
                tenantId = transaction.tenantId,
                paymentTransactionId = persistedTransaction.id,
                eventType = TransactionEventType.AUTHORIZATION,
                amount = persistedTransaction.authorizedAmount,
                idempotencyKey = idempotencyKey,
                resultSnapshot = resultSnapshot,
                metadata = authorizationMetadata(authorities),
                createdAt = recordedAt,
            ),
        )
        return RecordedAuthorization(
            transaction = persistedTransaction,
            event = event,
        )
    }

    fun recordMutation(
        transaction: PaymentTransaction,
        mutationKind: TransactionMutationKind,
        mutationAmount: Money,
        requestedAmount: Money?,
        reason: TransactionEventReason?,
        metadata: Metadata,
        recordedAt: Instant,
    ): RecordedMutation {
        val persistedTransaction = transactionRepository.update(transaction.tenantId, transaction)
        val eventId = TransactionEventId.of(idGenerator.newId())

        val eventType = when (mutationKind) {
            TransactionMutationKind.CAPTURE ->
                if (persistedTransaction.status == TransactionStatus.CAPTURED) {
                    TransactionEventType.CAPTURE
                } else {
                    TransactionEventType.PARTIAL_CAPTURE
                }

            TransactionMutationKind.RELEASE ->
                if (persistedTransaction.status == TransactionStatus.VOIDED) {
                    TransactionEventType.VOID
                } else {
                    TransactionEventType.ADJUSTMENT
                }

            TransactionMutationKind.REVERSAL ->
                TransactionEventType.REVERSAL
        }

        val event = transactionEventRepository.insert(
            transaction.tenantId,
            TransactionEvent(
                id = eventId,
                tenantId = transaction.tenantId,
                paymentTransactionId = persistedTransaction.id,
                eventType = eventType,
                amount = mutationAmount,
                requestedAmount = requestedAmount,
                reason = reason,
                metadata = metadata,
                createdAt = recordedAt,
            ),
        )
        return RecordedMutation(
            transaction = persistedTransaction,
            event = event,
        )
    }

    /**
     * Completes an event after the orchestrator has assembled the public result
     * and serialized it to an infrastructure-neutral snapshot.
     */
    fun completeIdempotentRecording(
        event: TransactionEvent,
        idempotencyKey: IdempotencyKey,
        resultSnapshot: TransactionResultSnapshot,
    ) {
        transactionEventRepository.completeIdempotency(
            tenantId = event.tenantId,
            eventId = event.id,
            idempotencyKey = idempotencyKey,
            resultSnapshot = resultSnapshot,
        )
    }

    private fun authorizationMetadata(
        authorities: List<ResolvedFundingAuthority>,
    ): Metadata =
        Metadata(
            mapOf(
                AUTHORITIES_KEY to authorities.map { authority ->
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

    private companion object {
        const val AUTHORITIES_KEY = "fundingAuthorities"
    }
}
