package io.github.sepfins.limitguard.application.change

import io.github.sepfins.limitguard.api.commands.RejectLimitRuleChangeCommand
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

internal class RejectLimitRuleChangeWorkflow(
    private val changeEventFactory: LimitChangeEventFactory,
    private val changeRequestRepository: LimitChangeRequestRepository,
    private val changeEventRepository: LimitRuleChangeEventRepository,
    private val clockProvider: ClockProvider,
    private val transactionRunner: TransactionRunner,
) {
    fun execute(command: RejectLimitRuleChangeCommand): LimitChangeRequestResult = mapValidation {
        require(command.decisionReason.isNotBlank()) { "decisionReason must not be blank" }
        val tenantId = TenantId.of(command.tenantId)
        val changeRequestId = LimitChangeRequestId.of(command.changeRequestId)
        val actor = command.rejectedBy.toChangeActor()
        val now = clockProvider.now()

        transactionRunner.inTransaction {
            val request = changeRequestRepository.findByIdForUpdate(tenantId, changeRequestId)
                ?: throw NotFoundException("limit change request not found: ${changeRequestId.value}")
            if (request.status != LimitRuleChangeStatus.REQUESTED) {
                throw ConflictException("limit change request is not awaiting a decision: ${request.id.value}")
            }

            val persistedRejected = changeRequestRepository.update(
                tenantId,
                request.reject(command.decisionReason, now),
            )
            changeEventRepository.insert(
                tenantId,
                changeEventFactory.rejected(
                    before = request,
                    after = persistedRejected,
                    now = now,
                    actor = actor,
                    eventPayload = Metadata(command.metadata),
                ),
            )
            persistedRejected.toResult()
        }
    }

    private inline fun <T> mapValidation(block: () -> T): T =
        try {
            block()
        } catch (exception: IllegalArgumentException) {
            throw ValidationException(
                message = exception.message ?: "limit change rejection validation failed",
                cause = exception,
            )
        }
}
