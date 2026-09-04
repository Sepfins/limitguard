package io.github.sepfins.limitguard.application.evaluation

import io.github.sepfins.limitguard.api.commands.ExpireDueAuthorizationsCommand
import io.github.sepfins.limitguard.api.errors.NotFoundException
import io.github.sepfins.limitguard.api.errors.ValidationException
import io.github.sepfins.limitguard.api.results.ExpireDueAuthorizationsResult
import io.github.sepfins.limitguard.api.results.UsageMutationResult
import io.github.sepfins.limitguard.application.transaction.TransactionMutationKind
import io.github.sepfins.limitguard.application.transaction.TransactionRecorder
import io.github.sepfins.limitguard.application.usage.UsageMutationService
import io.github.sepfins.limitguard.domain.common.Metadata
import io.github.sepfins.limitguard.domain.common.TenantId
import io.github.sepfins.limitguard.domain.transaction.PaymentTransaction
import io.github.sepfins.limitguard.domain.transaction.TransactionEventReason
import io.github.sepfins.limitguard.port.persistence.TenantRepository
import io.github.sepfins.limitguard.port.persistence.TransactionRepository
import io.github.sepfins.limitguard.port.time.ClockProvider
import io.github.sepfins.limitguard.port.transactions.TransactionRunner
import java.time.Instant

internal class ExpireDueAuthorizationsWorkflow(
    private val tenantRepository: TenantRepository,
    private val transactionRepository: TransactionRepository,
    private val usageMutationService: UsageMutationService,
    private val transactionRecorder: TransactionRecorder,
    private val clockProvider: ClockProvider,
    private val transactionRunner: TransactionRunner,
) {
    fun execute(command: ExpireDueAuthorizationsCommand): ExpireDueAuthorizationsResult {
        val tenantId = TenantId.of(command.tenantId)
        if (command.batchSize !in 1..MAX_BATCH_SIZE) {
            throw ValidationException("batchSize must be between 1 and $MAX_BATCH_SIZE")
        }
        tenantRepository.findById(tenantId)
            ?: throw NotFoundException("Tenant not found: ${tenantId.value}")

        val now = clockProvider.now()
        val candidates = transactionRepository.findIdsDueForExpiry(
            tenantId = tenantId,
            at = now,
            limit = command.batchSize,
        )
        val expirations = candidates.mapNotNull { candidate ->
            transactionRunner.inTransaction {
                val current = transactionRepository.findByIdForUpdate(tenantId, candidate)
                    ?: return@inTransaction null
                if (!current.isDueForExpiry(now)) {
                    return@inTransaction null
                }
                expire(current, now)
            }
        }

        return ExpireDueAuthorizationsResult(
            tenantId = tenantId.value,
            processedAt = now,
            expirations = expirations,
        )
    }

    private fun expire(
        transaction: PaymentTransaction,
        now: Instant,
    ): UsageMutationResult {
        val amount = transaction.uncapturedAmount
        val expiredTransaction = TransactionMutationKind.EXPIRY.apply(transaction, amount)
        val recorded = transactionRecorder.recordMutation(
            transaction = expiredTransaction,
            mutationKind = TransactionMutationKind.EXPIRY,
            mutationAmount = amount,
            reason = TransactionEventReason.of("Authorization expired"),
            metadata = Metadata.EMPTY,
            recordedAt = now,
        )
        val usageMutation = usageMutationService.mutate(
            transaction = recorded.transaction,
            mutationKind = TransactionMutationKind.EXPIRY,
            mutationEventId = recorded.event.id,
            amount = amount,
            now = now,
        )
        return recorded.toUsageMutationResult(usageMutation)
    }

    private companion object {
        const val MAX_BATCH_SIZE = 1_000
    }
}
