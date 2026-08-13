package io.github.sepfins.limitguard.application.evaluation

import io.github.sepfins.limitguard.api.LimitEvaluationService
import io.github.sepfins.limitguard.api.commands.AuthorizeTransactionCommand
import io.github.sepfins.limitguard.api.commands.CaptureTransactionCommand
import io.github.sepfins.limitguard.api.commands.ReleaseAuthorizationCommand
import io.github.sepfins.limitguard.api.commands.ReverseTransactionCommand
import io.github.sepfins.limitguard.api.errors.IdempotencyConflictException
import io.github.sepfins.limitguard.api.errors.NotFoundException
import io.github.sepfins.limitguard.api.errors.ValidationException
import io.github.sepfins.limitguard.api.results.AuthorizationResult
import io.github.sepfins.limitguard.api.results.UsageMutationResult
import io.github.sepfins.limitguard.application.transaction.TransactionIdempotency
import io.github.sepfins.limitguard.application.transaction.TransactionRecorder
import io.github.sepfins.limitguard.application.usage.UsageMutationService
import io.github.sepfins.limitguard.domain.common.IdempotencyKey
import io.github.sepfins.limitguard.domain.common.Metadata
import io.github.sepfins.limitguard.domain.transaction.PaymentTransaction
import io.github.sepfins.limitguard.domain.transaction.TransactionEvent
import io.github.sepfins.limitguard.domain.transaction.TransactionEventReason
import io.github.sepfins.limitguard.domain.transaction.TransactionEventType
import io.github.sepfins.limitguard.internal.hashing.CanonicalJson
import io.github.sepfins.limitguard.port.persistence.ChannelRepository
import io.github.sepfins.limitguard.port.persistence.FundingSourceOwnerRepository
import io.github.sepfins.limitguard.port.persistence.FundingSourceRepository
import io.github.sepfins.limitguard.port.persistence.OperationTypeRepository
import io.github.sepfins.limitguard.port.persistence.PartyProfileRepository
import io.github.sepfins.limitguard.port.persistence.TenantRepository
import io.github.sepfins.limitguard.port.persistence.TransactionEventRepository
import io.github.sepfins.limitguard.port.persistence.TransactionRepository
import io.github.sepfins.limitguard.port.time.ClockProvider
import io.github.sepfins.limitguard.port.transactions.TransactionRunner
import java.time.Instant

internal class DefaultLimitEvaluationService(
    private val transactionRepository: TransactionRepository,
    private val transactionEventRepository: TransactionEventRepository,
    private val partyProfileRepository: PartyProfileRepository,
    private val tenantRepository: TenantRepository,
    private val channelRepository: ChannelRepository,
    private val operationTypeRepository: OperationTypeRepository,
    private val fundingSourceRepository: FundingSourceRepository,
    private val fundingSourceOwnerRepository: FundingSourceOwnerRepository,
    private val transactionFactory: TransactionFactory,
    private val mandateResolver: MandateResolver,
    private val limitRuleMatcher: LimitRuleMatcher,
    private val authorizationEvaluator: AuthorizationEvaluator,
    private val usageMutationService: UsageMutationService,
    private val transactionRecorder: TransactionRecorder,
    private val clockProvider: ClockProvider,
    private val transactionRunner: TransactionRunner,
) : LimitEvaluationService {
    override fun authorizeTransaction(command: AuthorizeTransactionCommand): AuthorizationResult {
        val now = clockProvider.now()
        val requestedTransaction = validatedTransaction(command, now)
        val idempotencyKey = validatedIdempotencyKey(command.idempotencyKey)

        return transactionRunner.inTransaction {
            val existingEvent = idempotencyKey
                ?.let { transactionEventRepository.findByIdempotencyKey(requestedTransaction.tenantId, it) }
            if (existingEvent != null) {
                if (existingEvent.eventType != TransactionEventType.AUTHORIZATION) {
                    throw IdempotencyConflictException(
                        "idempotency key already belongs to a non-authorization transaction event",
                    )
                }
                val existingTransaction = transactionRepository.findById(
                    requestedTransaction.tenantId,
                    existingEvent.paymentTransactionId,
                ) ?: error(
                    "Authorization event ${existingEvent.id.value} references a missing payment transaction",
                )
                val replay = TransactionIdempotency.replayAuthorization(existingEvent.resultSnapshot!!)
                requireSameAuthorizationRequest(
                    existing = existingTransaction,
                    requested = requestedTransaction,
                    originalMetadata = replay.metadata,
                )
                return@inTransaction replay
            }

            requireAuthorizationReferences(requestedTransaction)
            val fundingSourceOwners = fundingSourceOwnerRepository.findByFundingSource(
                requestedTransaction.tenantId,
                requestedTransaction.fundingSourceId,
            )
            if (fundingSourceOwners.isEmpty()) {
                throw NotFoundException(
                    "Funding source has no owner: ${requestedTransaction.tenantId.value} / " +
                        requestedTransaction.fundingSourceId.value,
                )
            }

            val authorities = mandateResolver.resolve(
                tenantId = requestedTransaction.tenantId,
                actingPartyProfileId = requestedTransaction.actingPartyProfileId,
                fundingSourceId = requestedTransaction.fundingSourceId,
                owners = fundingSourceOwners,
                at = now,
            )
            if (authorities.isEmpty()) {
                return@inTransaction requestedTransaction.toAuthorizationResult(
                    AuthorizationEvaluator.actorNotAuthorized(requestedTransaction.authorizedAmount),
                )
            }

            val matchingRules = limitRuleMatcher.findMatches(
                transaction = requestedTransaction,
                authorities = authorities,
                at = now,
            )
            val evaluation = authorizationEvaluator.evaluate(
                transaction = requestedTransaction,
                matchedRules = matchingRules,
                now = now,
            )

            if (!evaluation.decision.allowed) {
                return@inTransaction requestedTransaction.toAuthorizationResult(evaluation.decision)
            }

            val recorded = transactionRecorder.recordAuthorization(
                transaction = requestedTransaction,
                matchedRules = evaluation.decision.matchedRules,
                authorities = authorities,
                idempotencyKey = idempotencyKey,
                recordedAt = now,
            )
            authorizationEvaluator.reserve(
                evaluation = evaluation,
                transactionEventId = recorded.event.id,
                now = now,
            )

            recorded.transaction.toAuthorizationResult(
                decision = evaluation.decision,
                transactionEventId = recorded.event.id,
            )
        }
    }

    override fun captureTransaction(command: CaptureTransactionCommand): UsageMutationResult =
        mutateTransaction(command.toMutationEventCommand())

    override fun releaseAuthorization(command: ReleaseAuthorizationCommand): UsageMutationResult =
        mutateTransaction(command.toMutationEventCommand())

    override fun reverseTransaction(command: ReverseTransactionCommand): UsageMutationResult =
        mutateTransaction(command.toMutationEventCommand())

    private fun mutateTransaction(command: MutationEventCommand): UsageMutationResult {
        val now = clockProvider.now()
        val idempotencyKey = validatedIdempotencyKey(command.idempotencyKeyRaw)
        val reason = validatedTransactionEventReason(command.reason)
        val metadata = Metadata(command.metadata)

        return transactionRunner.inTransaction {
            val existingEvent = idempotencyKey
                ?.let { transactionEventRepository.findByIdempotencyKey(command.tenantId, it) }
            if (existingEvent != null) {
                val sameRequest =
                    existingEvent.eventType in command.mutationKind.transactionEventTypes &&
                        existingEvent.paymentTransactionId == command.transactionId &&
                        sameRequestedAmount(existingEvent, command) &&
                        existingEvent.reason == reason &&
                        sameMetadata(existingEvent.metadata.value, metadata.value)
                if (!sameRequest) {
                    throw IdempotencyConflictException(
                        "idempotency key already belongs to a different " +
                            "${command.mutationKind.operationName} request",
                    )
                }
                return@inTransaction TransactionIdempotency.replayMutation(existingEvent.resultSnapshot!!)
            }

            val existingTransaction = transactionRepository.findByIdForUpdate(
                tenantId = command.tenantId,
                id = command.transactionId,
            ) ?: throw NotFoundException(
                "Transaction with id ${command.tenantId.value} / ${command.transactionId.value} not found",
            )

            val (mutatedTransaction, mutationAmount) = validatedMutation(existingTransaction, command)
            val recorded = transactionRecorder.recordMutation(
                transaction = mutatedTransaction,
                mutationKind = command.mutationKind,
                mutationAmount = mutationAmount,
                requestedAmount = mutationAmount.takeIf { command.amount != null },
                reason = reason,
                metadata = metadata,
                recordedAt = now,
            )
            val usageMutation = usageMutationService.mutate(
                transaction = recorded.transaction,
                mutationKind = command.mutationKind,
                mutationEventId = recorded.event.id,
                amount = mutationAmount,
                now = now,
            )
            val result = recorded.toUsageMutationResult(usageMutation)

            if (idempotencyKey != null) {
                transactionRecorder.completeIdempotentRecording(
                    event = recorded.event,
                    idempotencyKey = idempotencyKey,
                    resultSnapshot = TransactionIdempotency.usageMutationResultSnapshot(result),
                )
            }

            result
        }
    }

    private fun sameRequestedAmount(
        event: TransactionEvent,
        command: MutationEventCommand,
    ): Boolean =
        when {
            event.requestedAmount == null -> command.amount == null
            command.amount == null -> false
            else -> event.requestedAmount.amount.compareTo(command.amount) == 0
        }

    private fun sameMetadata(
        persisted: Map<String, Any?>,
        requested: Map<String, Any?>,
    ): Boolean =
        CanonicalJson.encodeObject(persisted) == CanonicalJson.encodeObject(requested)

    private fun requireAuthorizationReferences(transaction: PaymentTransaction) {
        tenantRepository.findById(transaction.tenantId)
            ?: throw NotFoundException("Tenant not found: ${transaction.tenantId.value}")
        channelRepository.findById(transaction.tenantId, transaction.channelId)
            ?: throw NotFoundException(
                "Channel not found: ${transaction.tenantId.value} / ${transaction.channelId.value}",
            )
        operationTypeRepository.findById(transaction.tenantId, transaction.operationTypeId)
            ?: throw NotFoundException(
                "Operation type not found: ${transaction.tenantId.value} / ${transaction.operationTypeId.value}",
            )
        partyProfileRepository.findById(transaction.tenantId, transaction.actingPartyProfileId)
            ?: throw NotFoundException(
                "Party profile not found: ${transaction.tenantId.value} / " +
                    transaction.actingPartyProfileId.value,
            )
        fundingSourceRepository.findById(transaction.tenantId, transaction.fundingSourceId)
            ?: throw NotFoundException(
                "Funding source not found: ${transaction.tenantId.value} / ${transaction.fundingSourceId.value}",
            )
    }

    private fun validatedTransaction(
        command: AuthorizeTransactionCommand,
        now: Instant,
    ): PaymentTransaction =
        try {
            transactionFactory.authorized(command, now)
        } catch (exception: IllegalArgumentException) {
            throw ValidationException(
                message = exception.message ?: "transaction authorization validation failed",
                cause = exception,
            )
        }

    private fun validatedIdempotencyKey(rawKey: String?): IdempotencyKey? =
        try {
            rawKey?.let(IdempotencyKey::of)
        } catch (exception: IllegalArgumentException) {
            throw ValidationException(
                message = exception.message ?: "idempotency key validation failed",
                cause = exception,
            )
        }

    private fun requireSameAuthorizationRequest(
        existing: PaymentTransaction,
        requested: PaymentTransaction,
        originalMetadata: Map<String, Any?>,
    ) {
        val sameRequest =
            existing.actingPartyProfileId == requested.actingPartyProfileId &&
                existing.fundingSourceId == requested.fundingSourceId &&
                existing.operationTypeId == requested.operationTypeId &&
                existing.channelId == requested.channelId &&
                existing.authorizedAmount == requested.authorizedAmount &&
                existing.expiresAt == requested.expiresAt &&
                sameMetadata(originalMetadata, requested.metadata.value)

        if (!sameRequest) {
            throw IdempotencyConflictException(
                "idempotency key already belongs to a different transaction authorization",
            )
        }
    }

    private fun validatedMutation(
        transaction: PaymentTransaction,
        command: MutationEventCommand,
    ) =
        try {
            val amount = command.mutationKind.amount(transaction, command.amount)
            command.mutationKind.apply(transaction, amount) to amount
        } catch (exception: IllegalArgumentException) {
            throw ValidationException(
                message = "Invalid ${command.mutationKind.operationName} request: ${exception.message}",
                cause = exception,
            )
        }

    private fun validatedTransactionEventReason(rawReason: String?): TransactionEventReason? =
        try {
            rawReason?.let(TransactionEventReason::of)
        } catch (exception: IllegalArgumentException) {
            throw ValidationException(
                message = "Invalid mutation reason: ${exception.message}",
                cause = exception,
            )
        }
}
