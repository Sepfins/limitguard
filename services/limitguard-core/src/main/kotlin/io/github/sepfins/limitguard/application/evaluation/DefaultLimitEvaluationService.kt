package io.github.sepfins.limitguard.application.evaluation

import io.github.sepfins.limitguard.api.LimitEvaluationService
import io.github.sepfins.limitguard.api.commands.AuthorizeTransactionCommand
import io.github.sepfins.limitguard.api.commands.CaptureTransactionCommand
import io.github.sepfins.limitguard.api.commands.ExpireDueAuthorizationsCommand
import io.github.sepfins.limitguard.api.commands.ReleaseAuthorizationCommand
import io.github.sepfins.limitguard.api.commands.ReverseTransactionCommand
import io.github.sepfins.limitguard.api.errors.AuthorizationExpiredException
import io.github.sepfins.limitguard.api.errors.IdempotencyConflictException
import io.github.sepfins.limitguard.api.errors.NotFoundException
import io.github.sepfins.limitguard.api.errors.ValidationException
import io.github.sepfins.limitguard.api.results.AuthorizationResult
import io.github.sepfins.limitguard.api.results.ExpireDueAuthorizationsResult
import io.github.sepfins.limitguard.api.results.UsageMutationResult
import io.github.sepfins.limitguard.application.transaction.TransactionIdempotency
import io.github.sepfins.limitguard.application.transaction.TransactionMutationKind
import io.github.sepfins.limitguard.application.transaction.TransactionRecorder
import io.github.sepfins.limitguard.application.usage.UsageMutationService
import io.github.sepfins.limitguard.domain.common.IdempotencyKey
import io.github.sepfins.limitguard.domain.common.Metadata
import io.github.sepfins.limitguard.domain.transaction.PaymentTransaction
import io.github.sepfins.limitguard.domain.transaction.TransactionEvent
import io.github.sepfins.limitguard.domain.transaction.TransactionEventReason
import io.github.sepfins.limitguard.internal.hashing.CanonicalJson
import io.github.sepfins.limitguard.port.persistence.FundingSourceOwnerRepository
import io.github.sepfins.limitguard.port.persistence.TransactionEventRepository
import io.github.sepfins.limitguard.port.persistence.TransactionRepository
import io.github.sepfins.limitguard.port.time.ClockProvider
import io.github.sepfins.limitguard.port.transactions.TransactionRunner
import java.time.Instant

internal class DefaultLimitEvaluationService(
    private val authorizationDecisionRecorder: AuthorizationDecisionRecorder,
    private val transactionRepository: TransactionRepository,
    private val transactionEventRepository: TransactionEventRepository,
    private val fundingSourceOwnerRepository: FundingSourceOwnerRepository,
    private val authorizationReferenceResolver: AuthorizationReferenceResolver,
    private val transactionFactory: TransactionFactory,
    private val mandateResolver: MandateResolver,
    private val limitRuleMatcher: LimitRuleMatcher,
    private val authorizationEvaluator: AuthorizationEvaluator,
    private val usageMutationService: UsageMutationService,
    private val transactionRecorder: TransactionRecorder,
    private val expireDueAuthorizationsWorkflow: ExpireDueAuthorizationsWorkflow,
    private val clockProvider: ClockProvider,
    private val transactionRunner: TransactionRunner,
) : LimitEvaluationService {
    override fun authorizeTransaction(command: AuthorizeTransactionCommand): AuthorizationResult {
        val now = clockProvider.now()
        val requestedTransaction = validatedTransaction(command, now)
        val idempotencyKey = validatedIdempotencyKey(command.idempotencyKey)

        return transactionRunner.inTransaction {
            replayAuthorizationIfPresent(
                requestedTransaction = requestedTransaction,
                idempotencyKey = idempotencyKey,
            )?.let { return@inTransaction it }

            requireFutureAuthorizationExpiry(requestedTransaction, now)

            val references = authorizationReferenceResolver.resolve(requestedTransaction)
            val unavailableReferences = references.unavailableReferenceViolations()
            if (unavailableReferences.isNotEmpty()) {
                return@inTransaction authorizationDecisionRecorder.record(
                    requestedTransaction = requestedTransaction,
                    decision = AuthorizationEvaluator.deniedForUnavailableReferences(
                        attemptedAmount = requestedTransaction.authorizedAmount,
                        unavailableReferences = unavailableReferences,
                    ),
                    authorities = emptyList(),
                    idempotencyKey = idempotencyKey,
                    recordedAt = now,
                )
            }

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
                return@inTransaction authorizationDecisionRecorder.record(
                    requestedTransaction = requestedTransaction,
                    decision = AuthorizationEvaluator.actorNotAuthorized(requestedTransaction.authorizedAmount),
                    authorities = emptyList(),
                    idempotencyKey = idempotencyKey,
                    recordedAt = now,
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
                noMatchingRuleBehavior = references.tenant.noMatchingRuleBehavior,
                now = now,
            )

            if (!evaluation.decision.allowed) {
                return@inTransaction authorizationDecisionRecorder.record(
                    requestedTransaction = requestedTransaction,
                    decision = evaluation.decision,
                    authorities = authorities,
                    idempotencyKey = idempotencyKey,
                    recordedAt = now,
                )
            }

            val recorded = transactionRecorder.recordAuthorization(
                transaction = requestedTransaction,
                authorities = authorities,
                recordedAt = now,
            )
            authorizationEvaluator.reserve(
                evaluation = evaluation,
                transactionEventId = recorded.event.id,
                now = now,
            )

            authorizationDecisionRecorder.record(
                requestedTransaction = requestedTransaction,
                decision = evaluation.decision,
                authorities = authorities,
                paymentTransaction = recorded.transaction,
                transactionEventId = recorded.event.id,
                idempotencyKey = idempotencyKey,
                recordedAt = now,
            )
        }
    }

    override fun captureTransaction(command: CaptureTransactionCommand): UsageMutationResult =
        mutateTransaction(command.toMutationEventCommand())

    override fun releaseAuthorization(command: ReleaseAuthorizationCommand): UsageMutationResult =
        mutateTransaction(command.toMutationEventCommand())

    override fun reverseTransaction(command: ReverseTransactionCommand): UsageMutationResult =
        mutateTransaction(command.toMutationEventCommand())

    override fun expireDueAuthorizations(
        command: ExpireDueAuthorizationsCommand,
    ): ExpireDueAuthorizationsResult =
        expireDueAuthorizationsWorkflow.execute(command)

    private fun replayAuthorizationIfPresent(
        requestedTransaction: PaymentTransaction,
        idempotencyKey: IdempotencyKey?,
    ): AuthorizationResult? {
        idempotencyKey ?: return null

        authorizationDecisionRecorder.findByIdempotencyKey(
            requestedTransaction.tenantId,
            idempotencyKey,
        )?.let { existing ->
            val result = TransactionIdempotency.replayAuthorization(existing.resultSnapshot)
            val sameRequest =
                existing.actingPartyProfileId == requestedTransaction.actingPartyProfileId &&
                    existing.fundingSourceId == requestedTransaction.fundingSourceId &&
                    existing.operationTypeId == requestedTransaction.operationTypeId &&
                    existing.channelId == requestedTransaction.channelId &&
                    existing.requestedAmount == requestedTransaction.authorizedAmount &&
                    existing.expiresAt == requestedTransaction.expiresAt &&
                    sameMetadata(result.metadata, requestedTransaction.metadata.value)
            if (!sameRequest) {
                throw IdempotencyConflictException(
                    "idempotency key already belongs to a different authorization request",
                )
            }
            return result
        }

        if (transactionEventRepository.findByIdempotencyKey(requestedTransaction.tenantId, idempotencyKey) != null) {
            throw IdempotencyConflictException(
                "idempotency key already belongs to a transaction event",
            )
        }
        return null
    }

    private fun mutateTransaction(command: MutationEventCommand): UsageMutationResult {
        val now = clockProvider.now()
        val idempotencyKey = validatedIdempotencyKey(command.idempotencyKeyRaw)
        val reason = validatedTransactionEventReason(command.reason)
        val metadata = Metadata(command.metadata)

        return transactionRunner.inTransaction {
            if (
                idempotencyKey != null &&
                authorizationDecisionRecorder.findByIdempotencyKey(command.tenantId, idempotencyKey) != null
            ) {
                throw IdempotencyConflictException(
                    "idempotency key already belongs to an authorization request",
                )
            }

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

            if (
                command.mutationKind in EXPIRY_GUARDED_MUTATIONS &&
                existingTransaction.isDueForExpiry(now)
            ) {
                throw AuthorizationExpiredException(
                    "Authorization ${existingTransaction.id.value} expired at ${existingTransaction.expiresAt}",
                )
            }

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

    private fun requireFutureAuthorizationExpiry(
        transaction: PaymentTransaction,
        now: Instant,
    ) {
        if (transaction.expiresAt != null && !transaction.expiresAt.isAfter(now)) {
            throw ValidationException("expiresAt must be later than the authorization time")
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

    private companion object {
        val EXPIRY_GUARDED_MUTATIONS = setOf(
            TransactionMutationKind.CAPTURE,
            TransactionMutationKind.RELEASE,
        )
    }
}
