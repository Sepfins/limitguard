package io.github.sepfins.limitguard.application.change

import io.github.sepfins.limitguard.api.commands.CancelLimitRuleChangeCommand
import io.github.sepfins.limitguard.api.TrustedChangeActorContext
import io.github.sepfins.limitguard.api.errors.ConflictException
import io.github.sepfins.limitguard.api.errors.ValidationException
import io.github.sepfins.limitguard.api.results.LimitChangeRequestResult
import io.github.sepfins.limitguard.domain.common.LimitChangeRequestId
import io.github.sepfins.limitguard.domain.common.Metadata
import io.github.sepfins.limitguard.domain.common.TenantId
import io.github.sepfins.limitguard.port.time.ClockProvider
import io.github.sepfins.limitguard.port.transactions.TransactionRunner

internal class CancelLimitRuleChangeWorkflow(
    private val eligibility: LimitChangeEligibilityValidator,
    private val transitions: LimitChangeTransitionRecorder,
    private val locks: LimitChangeLocks,
    private val clockProvider: ClockProvider,
    private val transactionRunner: TransactionRunner,
) {
    fun execute(command: CancelLimitRuleChangeCommand, trustedActorContext: TrustedChangeActorContext?): LimitChangeRequestResult = mapValidation {
        command.cancelledBy.requireTrustedAuthority(trustedActorContext)
        require(command.decisionReason == null || command.decisionReason.isNotBlank()) {
            "decisionReason must not be blank"
        }
        val tenantId = TenantId.of(command.tenantId)
        val changeRequestId = LimitChangeRequestId.of(command.changeRequestId)
        val actor = command.cancelledBy.toChangeActor()
        val now = clockProvider.now()

        transactionRunner.inTransaction {
            val request = locks.acquireRequest(tenantId, changeRequestId).request
            if (!request.status.isOpen) {
                throw ConflictException("limit change request cannot be cancelled: ${request.id.value}")
            }
            eligibility.validateActionActor(tenantId, actor)

            transitions.cancel(request, command.decisionReason, actor, now, Metadata(command.metadata)).toResult()
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
}
