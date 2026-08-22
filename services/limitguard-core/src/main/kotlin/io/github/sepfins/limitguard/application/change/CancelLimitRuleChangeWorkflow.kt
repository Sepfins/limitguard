package io.github.sepfins.limitguard.application.change

import io.github.sepfins.limitguard.api.commands.CancelLimitRuleChangeCommand
import io.github.sepfins.limitguard.api.errors.ConflictException
import io.github.sepfins.limitguard.api.errors.NotFoundException
import io.github.sepfins.limitguard.api.errors.ValidationException
import io.github.sepfins.limitguard.api.results.LimitChangeRequestResult
import io.github.sepfins.limitguard.domain.change.LimitRuleChangeStatus
import io.github.sepfins.limitguard.domain.common.LimitChangeRequestId
import io.github.sepfins.limitguard.domain.common.Metadata
import io.github.sepfins.limitguard.domain.common.TenantId
import io.github.sepfins.limitguard.port.persistence.LimitChangeRequestRepository
import io.github.sepfins.limitguard.port.persistence.LimitRuleChangeEventRepository
import io.github.sepfins.limitguard.port.time.ClockProvider
import io.github.sepfins.limitguard.port.transactions.TransactionRunner

internal class CancelLimitRuleChangeWorkflow(
    private val changeEventFactory: LimitChangeEventFactory,
    private val changeRequestRepository: LimitChangeRequestRepository,
    private val changeEventRepository: LimitRuleChangeEventRepository,
    private val clockProvider: ClockProvider,
    private val transactionRunner: TransactionRunner,
) {
    fun execute(command: CancelLimitRuleChangeCommand): LimitChangeRequestResult = mapValidation {
        require(command.decisionReason == null || command.decisionReason.isNotBlank()) {
            "decisionReason must not be blank"
        }
        val tenantId = TenantId.of(command.tenantId)
        val changeRequestId = LimitChangeRequestId.of(command.changeRequestId)
        val actor = command.cancelledBy.toChangeActor()
        val now = clockProvider.now()

        transactionRunner.inTransaction {
            val request = changeRequestRepository.findById(tenantId, changeRequestId)
                ?: throw NotFoundException("limit change request not found: ${changeRequestId.value}")
            if (request.status !in CANCELLABLE_STATUSES) {
                throw ConflictException("limit change request cannot be cancelled: ${request.id.value}")
            }

            val persistedCancelled = changeRequestRepository.update(
                tenantId,
                request.cancel(command.decisionReason, now),
            )
            changeEventRepository.insert(
                tenantId,
                changeEventFactory.cancelled(
                    before = request,
                    after = persistedCancelled,
                    now = now,
                    actor = actor,
                    eventPayload = Metadata(command.metadata),
                ),
            )
            persistedCancelled.toResult()
        }
    }

    private inline fun <T> mapValidation(block: () -> T): T =
        try {
            block()
        } catch (exception: IllegalArgumentException) {
            throw ValidationException(
                message = exception.message ?: "limit change cancellation validation failed",
                cause = exception,
            )
        }

    private companion object {
        val CANCELLABLE_STATUSES = setOf(
            LimitRuleChangeStatus.REQUESTED,
            LimitRuleChangeStatus.APPROVED,
            LimitRuleChangeStatus.PENDING_EFFECTIVE_DATE,
        )
    }
}
