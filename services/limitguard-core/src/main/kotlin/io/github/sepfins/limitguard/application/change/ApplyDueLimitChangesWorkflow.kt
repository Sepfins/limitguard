package io.github.sepfins.limitguard.application.change

import io.github.sepfins.limitguard.api.commands.ApplyDueLimitChangesCommand
import io.github.sepfins.limitguard.api.errors.ConflictException
import io.github.sepfins.limitguard.api.errors.NotFoundException
import io.github.sepfins.limitguard.api.errors.ValidationException
import io.github.sepfins.limitguard.api.results.ApplyDueChangesResult
import io.github.sepfins.limitguard.domain.change.ChangeActor
import io.github.sepfins.limitguard.domain.change.LimitRuleChangeRequest
import io.github.sepfins.limitguard.domain.common.LimitChangeRequestId
import io.github.sepfins.limitguard.domain.common.TenantId
import io.github.sepfins.limitguard.port.persistence.LimitChangeRequestRepository
import io.github.sepfins.limitguard.port.persistence.LimitRuleChangeEventRepository
import io.github.sepfins.limitguard.port.time.ClockProvider
import io.github.sepfins.limitguard.port.transactions.TransactionRunner
import java.time.Instant

internal class ApplyDueLimitChangesWorkflow(
    private val eligibility: LimitChangeEligibilityValidator,
    private val applicationWorkflow: LimitChangeApplicationWorkflow,
    private val changeEventFactory: LimitChangeEventFactory,
    private val changeRequestRepository: LimitChangeRequestRepository,
    private val changeEventRepository: LimitRuleChangeEventRepository,
    private val locks: LimitChangeLocks,
    private val clockProvider: ClockProvider,
    private val transactionRunner: TransactionRunner,
) {
    fun execute(command: ApplyDueLimitChangesCommand): ApplyDueChangesResult = mapValidation {
        require(command.limit > 0) { "limit must be greater than zero" }
        val tenantId = TenantId.of(command.tenantId)
        val dueAt = command.dueAt ?: clockProvider.now()

        transactionRunner.inTransaction {
            eligibility.validateTenant(tenantId)
            val candidates = changeRequestRepository.findIdsDueForApplication(
                tenantId = tenantId,
                dueAt = dueAt,
                limit = command.limit,
            )
            // A request another worker holds is skipped; its target is that worker's.
            val dueRequests = candidates.sortedBy { it.value.toString() }.mapNotNull {
                changeRequestRepository.claimForApplication(tenantId, it, dueAt)
            }.sortedWith(compareBy<LimitRuleChangeRequest> { it.createdAt }.thenBy { it.id.value.toString() })
            val lockedTargets = locks.acquireTargets(tenantId, dueRequests)
            val appliedRequestIds = mutableListOf<LimitChangeRequestId>()
            val failedRequestIds = mutableListOf<LimitChangeRequestId>()

            dueRequests.forEach { request ->
                if (request.expiresAt != null && request.expiresAt <= dueAt) {
                    markFailed(request, dueAt, DEADLINE_ELAPSED)
                    failedRequestIds += request.id
                    return@forEach
                }
                try {
                    transactionRunner.inSavepoint {
                        applicationWorkflow.applyInCurrentTransaction(
                            request = request,
                            target = lockedTargets.getValue(request.target),
                            actor = ChangeActor.System,
                            applicationTime = dueAt,
                        )
                    }
                    appliedRequestIds += request.id
                } catch (exception: ConflictException) {
                    markFailed(request, dueAt, exception.message)
                    failedRequestIds += request.id
                } catch (exception: NotFoundException) {
                    markFailed(request, dueAt, exception.message)
                    failedRequestIds += request.id
                } catch (exception: ValidationException) {
                    markFailed(request, dueAt, exception.message)
                    failedRequestIds += request.id
                } catch (exception: IllegalArgumentException) {
                    markFailed(request, dueAt, exception.message)
                    failedRequestIds += request.id
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
    }

    private fun markFailed(
        request: LimitRuleChangeRequest,
        dueAt: Instant,
        reason: String?,
    ) {
        val failureReason = reason ?: "scheduled limit change application failed"
        val persistedFailed = changeRequestRepository.update(
            request.tenantId,
            request.fail(failureReason, dueAt),
        )
        changeEventRepository.insert(
            request.tenantId,
            changeEventFactory.failed(
                before = request,
                after = persistedFailed,
                now = dueAt,
                failureReason = failureReason,
            ),
        )
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
        const val DEADLINE_ELAPSED = "limit change request deadline elapsed"
    }
}
