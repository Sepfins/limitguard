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
import io.github.sepfins.limitguard.application.transaction.TransactionRecorder
import io.github.sepfins.limitguard.application.transaction.TransactionIdempotency
import io.github.sepfins.limitguard.application.usage.UsageConsumptionService
import io.github.sepfins.limitguard.domain.common.IdempotencyKey
import io.github.sepfins.limitguard.domain.common.Metadata
import io.github.sepfins.limitguard.domain.common.Money
import io.github.sepfins.limitguard.domain.common.PaymentTransactionId
import io.github.sepfins.limitguard.domain.common.TenantId
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
    private val usageConsumptionService: UsageConsumptionService,
    private val transactionRecorder: TransactionRecorder,
    private val clockProvider: ClockProvider,
    private val transactionRunner: TransactionRunner,
): LimitEvaluationService {
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
                val replay = TransactionIdempotency.replayAuthorization(
                    existingEvent.resultSnapshot
                        ?: error("Idempotent authorization event ${existingEvent.id.value} has no result snapshot"),
                )
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

    override fun captureTransaction(command: CaptureTransactionCommand): UsageMutationResult {
        val now = clockProvider.now()
        val tenantId = TenantId.of(command.tenantId)
        val transactionId = PaymentTransactionId.of(command.paymentTransactionId)
        val idempotencyKey = validatedIdempotencyKey(command.idempotencyKey)
        val reason = validatedTransactionEventReason(command.reason)
        val metadata = Metadata(command.metadata)

        return transactionRunner.inTransaction {
            val existingEvent = idempotencyKey
                ?.let { transactionEventRepository.findByIdempotencyKey(tenantId, it) }
            if (existingEvent != null) {
                val sameRequest =
                    existingEvent.eventType in CAPTURE_EVENT_TYPES &&
                        existingEvent.paymentTransactionId == transactionId &&
                        sameRequestedAmount(existingEvent, command) &&
                        existingEvent.reason == reason &&
                        CanonicalJson.encodeObject(existingEvent.metadata.value) ==
                        CanonicalJson.encodeObject(metadata.value)
                if (!sameRequest) {
                    throw IdempotencyConflictException(
                        "idempotency key already belongs to a different capture request",
                    )
                }
                return@inTransaction TransactionIdempotency.replayCapture(
                    snapshot = existingEvent.resultSnapshot
                        ?: error("Idempotent capture event ${existingEvent.id.value} has no result snapshot"),
                )
            }

            val existingTransaction = transactionRepository.findByIdForUpdate(tenantId, transactionId)
                ?: throw NotFoundException(
                    "Transaction with id ${tenantId.value} / ${transactionId.value} not found",
                )
            val (capturedTransaction, capturedAmount) = validatedCapture(existingTransaction, command)
            val recorded = transactionRecorder.recordCapture(
                transaction = capturedTransaction,
                captureAmount = capturedAmount,
                requestedAmount = capturedAmount.takeIf { command.amount != null },
                reason = reason,
                metadata = metadata,
                recordedAt = now,
            )

            val usageMutation = usageConsumptionService.consume(
                transaction = recorded.transaction,
                captureEventId = recorded.event.id,
                amount = capturedAmount,
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

    override fun releaseAuthorization(command: ReleaseAuthorizationCommand): UsageMutationResult =
        TODO()

    override fun reverseTransaction(command: ReverseTransactionCommand): UsageMutationResult =
        TODO()

    private fun sameRequestedAmount(
        event: TransactionEvent,
        command: CaptureTransactionCommand,
    ): Boolean =
        when {
            event.requestedAmount == null -> command.amount == null
            command.amount == null -> false
            else -> event.requestedAmount.amount.compareTo(command.amount) == 0
        }

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
                CanonicalJson.encodeObject(originalMetadata) ==
                CanonicalJson.encodeObject(requested.metadata.value)

        if (!sameRequest) {
            throw IdempotencyConflictException(
                "idempotency key already belongs to a different transaction authorization",
            )
        }
    }

    private fun validatedCapture(
        transaction: PaymentTransaction,
        command: CaptureTransactionCommand,
    ): Pair<PaymentTransaction, Money> =
        try {
            val amount = command.amount
                ?.let { Money.positive(it, transaction.authorizedAmount.currency) }
                ?: (transaction.authorizedAmount - transaction.capturedAmount)
            transaction.capture(amount) to amount
        } catch (exception: IllegalArgumentException) {
            throw ValidationException(
                message = "Invalid capture request: ${exception.message}",
                cause = exception,
            )
        }

    private fun validatedTransactionEventReason(rawReason: String?): TransactionEventReason? =
        try {
            rawReason?.let(TransactionEventReason::of)
        } catch (exception: IllegalArgumentException) {
            throw ValidationException(
                message = "Invalid capture reason: ${exception.message}",
                cause = exception,
            )
        }

    private companion object {
        val CAPTURE_EVENT_TYPES = setOf(
            TransactionEventType.CAPTURE,
            TransactionEventType.PARTIAL_CAPTURE,
        )
    }

}
