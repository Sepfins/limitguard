package io.github.sepfins.limitguard.demo.application

import io.github.sepfins.limitguard.api.AuthorizationDecision
import io.github.sepfins.limitguard.api.TransactionStatus
import io.github.sepfins.limitguard.api.Limitguard
import io.github.sepfins.limitguard.api.commands.AuthorizeTransactionCommand
import io.github.sepfins.limitguard.api.commands.CaptureTransactionCommand
import io.github.sepfins.limitguard.api.commands.ReleaseAuthorizationCommand
import io.github.sepfins.limitguard.api.commands.ReverseTransactionCommand
import io.github.sepfins.limitguard.api.queries.GetTransactionHistoryQuery
import io.github.sepfins.limitguard.demo.domain.AccountType
import io.github.sepfins.limitguard.demo.domain.DemoPayment
import io.github.sepfins.limitguard.demo.domain.DemoPaymentMutation
import io.github.sepfins.limitguard.demo.domain.DemoViolation
import io.github.sepfins.limitguard.demo.domain.PaymentMutationType
import io.github.sepfins.limitguard.demo.domain.ProcessingState
import io.github.sepfins.limitguard.demo.persistence.DemoBankRepository
import io.github.sepfins.limitguard.demo.web.dto.MoneyDto
import io.github.sepfins.limitguard.demo.web.dto.PaymentAuthorizationRequest
import io.github.sepfins.limitguard.demo.web.dto.PaymentAuthorizationResponse
import io.github.sepfins.limitguard.demo.web.dto.PaymentMutationRequest
import io.github.sepfins.limitguard.demo.web.dto.PaymentMutationResponse
import io.github.sepfins.limitguard.demo.web.dto.PaymentViolationResponse
import io.github.sepfins.limitguard.demo.web.error.DemoConflictException
import io.github.sepfins.limitguard.demo.web.error.DemoFieldError
import io.github.sepfins.limitguard.demo.web.error.DemoNotFoundException
import io.github.sepfins.limitguard.demo.web.error.DemoValidationException
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.util.UUID

@Service
class PaymentProcessingService(
    private val limitguard: Limitguard,
    private val scenarioService: DemoScenarioService,
    private val repository: DemoBankRepository,
    private val eventStream: DemoEventStream,
) {
    fun authorize(
        request: PaymentAuthorizationRequest,
        idempotencyKey: String,
    ): PaymentAuthorizationResponse {
        validateIdempotencyKey(idempotencyKey)
        val scenario = scenarioService.current()
        val account = repository.findAccount(scenario.id, request.accountId)
            ?: throw DemoNotFoundException("Account not found")
        if (account.accountType != AccountType.CURRENT) {
            throw DemoValidationException(
                "Payments are only enabled for the household current account",
                listOf(DemoFieldError("accountId", "must identify the current account")),
            )
        }
        val card = request.cardId?.let { cardId ->
            repository.findCard(scenario.id, cardId)
                ?: throw DemoNotFoundException("Card not found")
        }
        if (request.operation.requiresCard && card == null) {
            throw DemoValidationException(
                "A card is required for this operation",
                listOf(DemoFieldError("cardId", "is required")),
            )
        }
        if (!request.operation.requiresCard && card != null) {
            throw DemoValidationException(
                "A card is not valid for this operation",
                listOf(DemoFieldError("cardId", "must be omitted")),
            )
        }
        if (card != null && card.accountId != account.id) {
            throw DemoValidationException(
                "Card and account do not match",
                listOf(DemoFieldError("cardId", "must belong to accountId")),
            )
        }
        if (request.currency != account.currency) {
            throw DemoValidationException(
                "Payment currency does not match the account",
                listOf(DemoFieldError("currency", "must be ${account.currency}")),
            )
        }
        val amount = parsePositiveAmount(request.amount, "amount")
        val now = Instant.now()
        val intent = DemoPayment(
            externalPaymentId = request.externalPaymentId,
            scenarioId = scenario.id,
            accountId = account.id,
            cardId = card?.id,
            operation = request.operation,
            description = request.description.trim(),
            category = request.category,
            requestedAmount = amount,
            currency = request.currency,
            authorizationIdempotencyKey = idempotencyKey,
            processingState = ProcessingState.PROCESSING,
            decision = null,
            authorizationDecisionId = null,
            paymentTransactionId = null,
            transactionStatus = null,
            authorizedAmount = BigDecimal.ZERO,
            capturedAmount = BigDecimal.ZERO,
            uncapturedAmount = BigDecimal.ZERO,
            violations = emptyList(),
            failureCode = null,
            createdAt = now,
            updatedAt = now,
        )
        val inserted = repository.insertPaymentIntent(intent)
        if (!inserted) {
            val existing = repository.findPayment(request.externalPaymentId)
                ?: repository.findPaymentByIdempotencyKey(scenario.id, idempotencyKey)
                ?: error("payment idempotency conflict was not persisted")
            requireSameAuthorization(existing, intent)
            if (existing.processingState == ProcessingState.COMPLETED) {
                return existing.toAuthorizationResponse(replayed = true)
            }
        }

        val setting = repository.listLimitSettings(scenario.id)
            .first { it.operation == request.operation }
        try {
            val result = limitguard.limitEvaluationService.authorizeTransaction(
                AuthorizeTransactionCommand(
                    tenantId = requireNotNull(scenario.tenantId),
                    actingPartyProfileId = requireNotNull(scenario.customerPartyProfileId),
                    fundingSourceId = account.fundingSourceId,
                    operationTypeId = setting.operationTypeId,
                    channelId = setting.channelId,
                    amount = amount,
                    currency = request.currency,
                    idempotencyKey = "demo-payment-auth:${request.externalPaymentId}:$idempotencyKey",
                    metadata = mapOf(
                        "externalPaymentId" to request.externalPaymentId,
                        "operation" to request.operation.name,
                        "description" to request.description.trim(),
                        "category" to request.category.name,
                    ),
                ),
            )
            val completed = intent.copy(
                processingState = ProcessingState.COMPLETED,
                decision = result.decision,
                authorizationDecisionId = result.authorizationDecisionId,
                paymentTransactionId = result.paymentTransactionId,
                transactionStatus = if (result.decision == AuthorizationDecision.ALLOWED) {
                    TransactionStatus.AUTHORIZED
                } else {
                    null
                },
                authorizedAmount = if (result.decision == AuthorizationDecision.ALLOWED) {
                    result.authorizedAmount
                } else {
                    BigDecimal.ZERO
                },
                uncapturedAmount = if (result.decision == AuthorizationDecision.ALLOWED) {
                    result.authorizedAmount
                } else {
                    BigDecimal.ZERO
                },
                violations = result.violations.map {
                    DemoViolation(
                        code = it.code,
                        message = it.message,
                        limitRuleId = it.limitRuleId,
                        limitAmount = it.limitAmount,
                        attemptedAmount = it.attemptedAmount,
                        remainingAmount = it.remainingAmount,
                    )
                },
                updatedAt = Instant.now(),
            )
            repository.completeAuthorization(completed)
            eventStream.publish("payments.updated", "payment", completed.externalPaymentId)
            return completed.toAuthorizationResponse(replayed = !inserted)
        } catch (exception: Exception) {
            repository.markPaymentFailed(request.externalPaymentId, failureCode(exception), Instant.now())
            throw exception
        }
    }

    fun capture(
        externalPaymentId: String,
        request: PaymentMutationRequest,
        idempotencyKey: String,
    ): PaymentMutationResponse =
        mutate(externalPaymentId, request, idempotencyKey, PaymentMutationType.CAPTURE)

    fun release(
        externalPaymentId: String,
        request: PaymentMutationRequest,
        idempotencyKey: String,
    ): PaymentMutationResponse =
        mutate(externalPaymentId, request, idempotencyKey, PaymentMutationType.RELEASE)

    fun reverse(
        externalPaymentId: String,
        request: PaymentMutationRequest,
        idempotencyKey: String,
    ): PaymentMutationResponse =
        mutate(externalPaymentId, request, idempotencyKey, PaymentMutationType.REVERSAL)

    private fun mutate(
        externalPaymentId: String,
        request: PaymentMutationRequest,
        idempotencyKey: String,
        mutationType: PaymentMutationType,
    ): PaymentMutationResponse {
        validateIdempotencyKey(idempotencyKey)
        val scenario = scenarioService.current()
        val payment = repository.findPayment(externalPaymentId)
            ?.takeIf { it.scenarioId == scenario.id }
            ?: throw DemoNotFoundException("Payment not found")
        val transactionId = payment.paymentTransactionId
            ?: throw DemoConflictException("Only allowed payments have a mutable transaction")
        val amount = request.amount?.let { parsePositiveAmount(it, "amount") }
        val reason = request.reason?.trim()?.takeIf { it.isNotEmpty() }
        val now = Instant.now()
        val intent = DemoPaymentMutation(
            id = UUID.randomUUID(),
            externalPaymentId = externalPaymentId,
            mutationType = mutationType,
            idempotencyKey = idempotencyKey,
            requestedAmount = amount,
            reason = reason,
            processingState = ProcessingState.PROCESSING,
            resultStatus = null,
            resultAmount = null,
            transactionEventId = null,
            authorizedAmount = null,
            capturedAmount = null,
            uncapturedAmount = null,
            createdAt = now,
            completedAt = null,
        )
        val prior = repository.findMutationByIdempotencyKey(idempotencyKey)
        if (prior != null) {
            requireSameMutation(prior, intent)
            if (prior.processingState == ProcessingState.COMPLETED) {
                return prior.toMutationResponse(payment.currency, replayed = true)
            }
        }

        validateLifecycle(payment.transactionStatus, mutationType)
        val inserted = prior == null && repository.insertMutationIntent(intent)
        var storedIntent = prior ?: intent
        if (prior == null && !inserted) {
            val existing = repository.findMutationByIdempotencyKey(idempotencyKey)
                ?: error("payment mutation idempotency conflict was not persisted")
            requireSameMutation(existing, intent)
            if (existing.processingState == ProcessingState.COMPLETED) {
                return existing.toMutationResponse(payment.currency, replayed = true)
            }
            storedIntent = existing
        }

        val coreKey = "demo-payment-${mutationType.name.lowercase()}:$externalPaymentId:$idempotencyKey"
        try {
            val result = when (mutationType) {
                PaymentMutationType.CAPTURE -> limitguard.limitEvaluationService.captureTransaction(
                    CaptureTransactionCommand(
                        tenantId = requireNotNull(scenario.tenantId),
                        paymentTransactionId = transactionId,
                        amount = amount,
                        reason = reason,
                        idempotencyKey = coreKey,
                    ),
                )
                PaymentMutationType.RELEASE -> limitguard.limitEvaluationService.releaseAuthorization(
                    ReleaseAuthorizationCommand(
                        tenantId = requireNotNull(scenario.tenantId),
                        paymentTransactionId = transactionId,
                        amount = amount,
                        reason = reason,
                        idempotencyKey = coreKey,
                    ),
                )
                PaymentMutationType.REVERSAL -> limitguard.limitEvaluationService.reverseTransaction(
                    ReverseTransactionCommand(
                        tenantId = requireNotNull(scenario.tenantId),
                        paymentTransactionId = transactionId,
                        amount = amount,
                        reason = reason,
                        idempotencyKey = coreKey,
                    ),
                )
            }
            val history = limitguard.limitInquiryService.getTransactionHistory(
                GetTransactionHistoryQuery(requireNotNull(scenario.tenantId), transactionId),
            ).transaction
            val completed = storedIntent.copy(
                processingState = ProcessingState.COMPLETED,
                resultStatus = result.status,
                resultAmount = result.amount,
                transactionEventId = result.transactionEventId,
                authorizedAmount = history.authorizedAmount,
                capturedAmount = history.capturedAmount,
                uncapturedAmount = history.uncapturedAmount,
                completedAt = Instant.now(),
            )
            repository.synchronizePayment(
                externalPaymentId = externalPaymentId,
                status = history.status,
                authorizedAmount = history.authorizedAmount,
                capturedAmount = history.capturedAmount,
                uncapturedAmount = history.uncapturedAmount,
                now = Instant.now(),
            )
            repository.completeMutation(completed)
            eventStream.publish("payments.updated", "payment", externalPaymentId)
            return completed.toMutationResponse(payment.currency, replayed = !inserted)
        } catch (exception: Exception) {
            repository.markMutationFailed(storedIntent.id, Instant.now())
            throw exception
        }
    }

    private fun validateLifecycle(status: TransactionStatus?, mutationType: PaymentMutationType) {
        val valid = when (mutationType) {
            PaymentMutationType.CAPTURE, PaymentMutationType.RELEASE ->
                status == TransactionStatus.AUTHORIZED || status == TransactionStatus.PARTIALLY_CAPTURED
            PaymentMutationType.REVERSAL -> status == TransactionStatus.CAPTURED
        }
        if (!valid) {
            throw DemoConflictException("${mutationType.name.lowercase()} is not valid for payment status $status")
        }
    }

    private fun requireSameAuthorization(existing: DemoPayment, requested: DemoPayment) {
        val same = existing.scenarioId == requested.scenarioId &&
            existing.accountId == requested.accountId &&
            existing.cardId == requested.cardId &&
            existing.operation == requested.operation &&
            existing.description == requested.description &&
            existing.category == requested.category &&
            existing.requestedAmount.compareTo(requested.requestedAmount) == 0 &&
            existing.currency == requested.currency &&
            existing.authorizationIdempotencyKey == requested.authorizationIdempotencyKey
        if (!same) {
            throw DemoConflictException("External payment ID or idempotency key was reused with a different request")
        }
    }

    private fun requireSameMutation(existing: DemoPaymentMutation, requested: DemoPaymentMutation) {
        val amountMatches = existing.requestedAmount == null && requested.requestedAmount == null ||
            existing.requestedAmount != null && requested.requestedAmount != null &&
            existing.requestedAmount.compareTo(requested.requestedAmount) == 0
        if (existing.externalPaymentId != requested.externalPaymentId ||
            existing.mutationType != requested.mutationType ||
            !amountMatches ||
            existing.reason != requested.reason
        ) {
            throw DemoConflictException("Idempotency key was reused with a different mutation request")
        }
    }

    private fun DemoPayment.toAuthorizationResponse(replayed: Boolean): PaymentAuthorizationResponse =
        PaymentAuthorizationResponse(
            externalPaymentId = externalPaymentId,
            decision = requireNotNull(decision),
            authorizationDecisionId = requireNotNull(authorizationDecisionId),
            paymentTransactionId = paymentTransactionId,
            transactionStatus = transactionStatus,
            amount = money(requestedAmount, currency),
            violations = violations.map {
                PaymentViolationResponse(
                    code = it.code,
                    message = it.message,
                    limitRuleId = it.limitRuleId,
                    limitAmount = it.limitAmount?.let { value -> money(value, currency) },
                    attemptedAmount = money(it.attemptedAmount, currency),
                    remainingAmount = it.remainingAmount?.let { value -> money(value, currency) },
                )
            },
            replayed = replayed,
        )

    private fun DemoPaymentMutation.toMutationResponse(
        currency: String,
        replayed: Boolean,
    ): PaymentMutationResponse =
        PaymentMutationResponse(
            externalPaymentId = externalPaymentId,
            transactionStatus = requireNotNull(resultStatus),
            mutationAmount = money(requireNotNull(resultAmount), currency),
            authorizedAmount = money(requireNotNull(authorizedAmount), currency),
            capturedAmount = money(requireNotNull(capturedAmount), currency),
            uncapturedAmount = money(requireNotNull(uncapturedAmount), currency),
            transactionEventId = transactionEventId,
            replayed = replayed,
        )

    private fun parsePositiveAmount(value: String, field: String): BigDecimal {
        val amount = try {
            BigDecimal(value)
        } catch (_: NumberFormatException) {
            throw DemoValidationException(
                "Payment amount is invalid",
                listOf(DemoFieldError(field, "must be a decimal amount")),
            )
        }
        if (amount.signum() <= 0 || amount.scale() > 2) {
            throw DemoValidationException(
                "Payment amount is invalid",
                listOf(DemoFieldError(field, "must be positive with at most two decimal places")),
            )
        }
        return amount
    }

    private fun validateIdempotencyKey(value: String) {
        if (value.isBlank() || value.length > 100) {
            throw DemoValidationException(
                "Idempotency key is invalid",
                listOf(DemoFieldError("Idempotency-Key", "must contain 1 to 100 characters")),
            )
        }
    }

    private fun money(amount: BigDecimal, currency: String): MoneyDto =
        MoneyDto(amount.setScale(2, RoundingMode.HALF_UP).toPlainString(), currency)

    private fun failureCode(exception: Exception): String =
        exception::class.simpleName?.take(80) ?: "PAYMENT_PROCESSING_FAILED"
}
