package io.github.sepfins.limitguard.application.change

import io.github.sepfins.limitguard.api.commands.ApproveLimitRuleChangeCommand
import io.github.sepfins.limitguard.api.TrustedChangeActorContext
import io.github.sepfins.limitguard.api.errors.ValidationException
import io.github.sepfins.limitguard.api.results.LimitChangeRequestResult
import io.github.sepfins.limitguard.domain.common.LimitChangeRequestId
import io.github.sepfins.limitguard.domain.common.Metadata
import io.github.sepfins.limitguard.domain.common.TenantId
import io.github.sepfins.limitguard.port.time.ClockProvider
import io.github.sepfins.limitguard.port.transactions.TransactionRunner

internal class ApproveLimitRuleChangeWorkflow(
    private val eligibility: LimitChangeEligibilityValidator,
    private val applicationWorkflow: LimitChangeApplicationWorkflow,
    private val locks: LimitChangeLocks,
    private val clockProvider: ClockProvider,
    private val transactionRunner: TransactionRunner,
) {
    fun execute(command: ApproveLimitRuleChangeCommand, trustedActorContext: TrustedChangeActorContext?): LimitChangeRequestResult = mapValidation {
        command.approvedBy.requireTrustedAuthority(trustedActorContext)
        require(command.decisionReason == null || command.decisionReason.isNotBlank()) {
            "decisionReason must not be blank"
        }
        val tenantId = TenantId.of(command.tenantId)
        val changeRequestId = LimitChangeRequestId.of(command.changeRequestId)
        val actor = command.approvedBy.toChangeActor()
        val now = clockProvider.now()

        transactionRunner.inTransaction {
            val locked = locks.acquireRequest(tenantId, changeRequestId)
            eligibility.validateActionActor(tenantId, actor)

            applicationWorkflow.approve(
                request = locked.request,
                target = locked.target,
                actor = actor,
                reason = command.decisionReason,
                at = now,
                eventPayload = Metadata(command.metadata),
            ).toResult()
        }
    }

    private inline fun <T> mapValidation(block: () -> T): T =
        try {
            block()
        } catch (exception: IllegalArgumentException) {
            throw ValidationException(
                message = exception.message ?: "limit change approval validation failed",
                cause = exception,
            )
        }
}
