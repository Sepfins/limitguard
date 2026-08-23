package io.github.sepfins.limitguard.application.change

import io.github.sepfins.limitguard.api.commands.ApplyDueLimitChangesCommand
import io.github.sepfins.limitguard.api.errors.NotFoundException
import io.github.sepfins.limitguard.api.errors.ValidationException
import io.github.sepfins.limitguard.api.results.ApplyDueChangesResult
import io.github.sepfins.limitguard.domain.change.ChangeActor
import io.github.sepfins.limitguard.domain.change.LimitRuleChangeStatus
import io.github.sepfins.limitguard.domain.common.LimitChangeRequestId
import io.github.sepfins.limitguard.domain.common.TenantId
import io.github.sepfins.limitguard.port.persistence.LimitChangeRequestRepository
import io.github.sepfins.limitguard.port.persistence.LimitRuleChangeEventRepository
import io.github.sepfins.limitguard.port.persistence.LimitRuleRepository
import io.github.sepfins.limitguard.port.time.ClockProvider
import io.github.sepfins.limitguard.port.transactions.TransactionRunner
import java.time.Instant

internal class ApplyDueLimitChangesWorkflow(
    private val applicationWorkflow: LimitChangeApplicationWorkflow,
    private val changeEventFactory: LimitChangeEventFactory,
    private val changeRequestRepository: LimitChangeRequestRepository,
    private val changeEventRepository: LimitRuleChangeEventRepository,
    private val limitRuleRepository: LimitRuleRepository,
    private val clockProvider: ClockProvider,
    private val transactionRunner: TransactionRunner,
) {
    fun execute(command: ApplyDueLimitChangesCommand): ApplyDueChangesResult = mapValidation {
        require(command.limit > 0) { "limit must be greater than zero" }
        val tenantId = TenantId.of(command.tenantId)
        val dueAt = command.dueAt ?: clockProvider.now()
        val dueRequests = changeRequestRepository.findDueForApplication(tenantId, dueAt, command.limit)
        val appliedRequestIds = mutableListOf<LimitChangeRequestId>()
        val failedRequestIds = mutableListOf<LimitChangeRequestId>()

        dueRequests.forEach { dueRequest ->
            try {
                apply(tenantId, dueRequest.id, dueAt)
                appliedRequestIds += dueRequest.id
            } catch (exception: RuntimeException) {
                markFailed(tenantId, dueRequest.id, dueAt, exception)
                failedRequestIds += dueRequest.id
            }
        }

        ApplyDueChangesResult(
            tenantId = tenantId.value,
            dueAt = dueAt,
            appliedCount = appliedRequestIds.size,
            failedCount = failedRequestIds.size,
            appliedChangeRequestIds = appliedRequestIds.map { it.value },
            failedChangeRequestIds = failedRequestIds.map { it.value },
        )
    }

    private fun apply(
        tenantId: TenantId,
        changeRequestId: LimitChangeRequestId,
        dueAt: Instant,
    ) {
        transactionRunner.inTransaction {
            val request = changeRequestRepository.findById(tenantId, changeRequestId)
                ?: throw NotFoundException("limit change request not found: ${changeRequestId.value}")
            val currentRule = request.limitRuleId?.let { limitRuleRepository.findById(tenantId, it) }
            val approvedRule = requireNotNull(request.approvedRule) {
                "approved rule is missing from change request: ${request.id.value}"
            }
            val candidate = LimitRuleSnapshotter.restoreRule(
                snapshot = approvedRule,
                createdAt = currentRule?.createdAt ?: request.createdAt,
                updatedAt = dueAt,
            )

            applicationWorkflow.applyInCurrentTransaction(
                request = request,
                candidate = candidate,
                actor = ChangeActor.System,
                applicationTime = dueAt,
            )
        }
    }

    private fun markFailed(
        tenantId: TenantId,
        changeRequestId: LimitChangeRequestId,
        dueAt: Instant,
        exception: RuntimeException,
    ) {
        transactionRunner.inTransaction {
            val request = changeRequestRepository.findById(tenantId, changeRequestId)
                ?: return@inTransaction
            if (request.status !in APPLICABLE_STATUSES) return@inTransaction

            val failureReason = exception.message ?: "scheduled limit change application failed"
            val persistedFailed = changeRequestRepository.update(
                tenantId,
                request.fail(failureReason, dueAt),
            )
            changeEventRepository.insert(
                tenantId,
                changeEventFactory.failed(
                    before = request,
                    after = persistedFailed,
                    now = dueAt,
                    failureReason = failureReason,
                ),
            )
        }
    }

    private inline fun <T> mapValidation(block: () -> T): T =
        try {
            block()
        } catch (exception: IllegalArgumentException) {
            throw ValidationException(
                message = exception.message ?: "apply due limit changes validation failed",
                cause = exception,
            )
        }

    private companion object {
        val APPLICABLE_STATUSES = setOf(
            LimitRuleChangeStatus.APPROVED,
            LimitRuleChangeStatus.PENDING_EFFECTIVE_DATE,
        )
    }
}
