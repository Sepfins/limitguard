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
import io.github.sepfins.limitguard.domain.transaction.PaymentTransaction
import io.github.sepfins.limitguard.port.persistence.ChannelRepository
import io.github.sepfins.limitguard.port.persistence.FundingSourceOwnerRepository
import io.github.sepfins.limitguard.port.persistence.FundingSourceRepository
import io.github.sepfins.limitguard.port.persistence.OperationTypeRepository
import io.github.sepfins.limitguard.port.persistence.PartyProfileRepository
import io.github.sepfins.limitguard.port.persistence.TenantRepository
import io.github.sepfins.limitguard.port.persistence.TransactionRepository
import io.github.sepfins.limitguard.port.time.ClockProvider
import io.github.sepfins.limitguard.port.transactions.TransactionRunner
import java.time.Instant

internal class DefaultLimitEvaluationService(
    private val transactionRepository: TransactionRepository,
    private val partyProfileRepository: PartyProfileRepository,
    private val tenantRepository: TenantRepository,
    private val channelRepository: ChannelRepository,
    private val operationTypeRepository: OperationTypeRepository,
    private val fundingSourceRepository: FundingSourceRepository,
    private val fundingSourceOwnerRepository: FundingSourceOwnerRepository,
    private val transactionFactory: TransactionFactory,
    private val mandateResolver: MandateResolver,
    private val limitRuleMatcher: LimitRuleMatcher,
    private val transactionRecorder: TransactionRecorder,
    private val clockProvider: ClockProvider,
    private val transactionRunner: TransactionRunner,
): LimitEvaluationService {
    override fun authorizeTransaction(command: AuthorizeTransactionCommand): AuthorizationResult {
        val now = clockProvider.now()
        val requestedTransaction = validatedTransaction(command, now)

        return transactionRunner.inTransaction {
            val existingTransaction = requestedTransaction.idempotencyKey
                ?.let { transactionRepository.findByIdempotencyKey(requestedTransaction.tenantId, it) }
            if (existingTransaction != null) {
                requireSameAuthorizationRequest(existingTransaction, requestedTransaction)
                val replay = transactionRecorder.replayAuthorization(existingTransaction)
                return@inTransaction replay.transaction.toAuthorizationResult(
                    decision = LimitDecision(
                        matchedRules = replay.matchedRules,
                        violations = emptyList(),
                    ),
                    transactionEventId = replay.event.id,
                )
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
                    LimitDecisionEngine.actorNotAuthorized(requestedTransaction.authorizedAmount),
                )
            }

            val matchingRules = limitRuleMatcher.findMatches(
                transaction = requestedTransaction,
                authorities = authorities,
                at = now,
            )
            val decision = LimitDecisionEngine.decide(
                attemptedAmount = requestedTransaction.authorizedAmount,
                rules = matchingRules,
            )
            if (!decision.allowed) {
                return@inTransaction requestedTransaction.toAuthorizationResult(decision)
            }

            val recorded = transactionRecorder.recordAuthorization(
                transaction = requestedTransaction,
                matchedRules = decision.matchedRules,
                authorities = authorities,
                recordedAt = now,
            )
            recorded.transaction.toAuthorizationResult(
                decision = decision,
                transactionEventId = recorded.event.id,
            )
        }
    }

    override fun captureTransaction(command: CaptureTransactionCommand): UsageMutationResult {
        TODO("Not yet implemented")
    }

    override fun releaseAuthorization(command: ReleaseAuthorizationCommand): UsageMutationResult {
        TODO("Not yet implemented")
    }

    override fun reverseTransaction(command: ReverseTransactionCommand): UsageMutationResult {
        TODO("Not yet implemented")
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

    private fun requireSameAuthorizationRequest(
        existing: PaymentTransaction,
        requested: PaymentTransaction,
    ) {
        val sameRequest =
            existing.actingPartyProfileId == requested.actingPartyProfileId &&
                existing.fundingSourceId == requested.fundingSourceId &&
                existing.operationTypeId == requested.operationTypeId &&
                existing.channelId == requested.channelId &&
                existing.authorizedAmount == requested.authorizedAmount &&
                existing.expiresAt == requested.expiresAt &&
                existing.metadata == requested.metadata

        if (!sameRequest) {
            throw IdempotencyConflictException(
                "idempotency key already belongs to a different transaction authorization",
            )
        }
    }
}
