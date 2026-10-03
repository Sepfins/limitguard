package io.github.sepfins.limitguard.application.change

import io.github.sepfins.limitguard.api.commands.ApplyDueLimitChangesCommand
import io.github.sepfins.limitguard.api.errors.ConflictException
import io.github.sepfins.limitguard.api.errors.NotFoundException
import io.github.sepfins.limitguard.api.errors.ValidationException
import io.github.sepfins.limitguard.api.results.ApplyDueChangesResult
import io.github.sepfins.limitguard.application.configuration.resolveHierarchy
import io.github.sepfins.limitguard.domain.change.ChangeActor
import io.github.sepfins.limitguard.domain.change.LimitRuleChangeRequest
import io.github.sepfins.limitguard.domain.common.LimitChangeRequestId
import io.github.sepfins.limitguard.domain.common.TenantId
import io.github.sepfins.limitguard.port.persistence.LimitChangeRequestRepository
import io.github.sepfins.limitguard.port.persistence.LimitConfigurationRepository
import io.github.sepfins.limitguard.port.persistence.LimitRuleChangeEventRepository
import io.github.sepfins.limitguard.port.persistence.LimitRuleRepository
import io.github.sepfins.limitguard.port.time.ClockProvider
import io.github.sepfins.limitguard.port.transactions.TransactionRunner
import java.time.Instant

internal class ApplyDueLimitChangesWorkflow(
    private val applicationWorkflow: LimitChangeApplicationWorkflow,
    private val mandateValidator: LimitRuleMandateValidator,
    private val changeEventFactory: LimitChangeEventFactory,
    private val changeRequestRepository: LimitChangeRequestRepository,
    private val changeEventRepository: LimitRuleChangeEventRepository,
    private val limitConfigurationRepository: LimitConfigurationRepository,
    private val limitRuleRepository: LimitRuleRepository,
    private val locks: LimitChangeLocks,
    private val clockProvider: ClockProvider,
    private val transactionRunner: TransactionRunner,
) {
    fun execute(command: ApplyDueLimitChangesCommand): ApplyDueChangesResult = mapValidation {
        require(command.limit > 0) { "limit must be greater than zero" }
        val tenantId = TenantId.of(command.tenantId)
        val dueAt = command.dueAt ?: clockProvider.now()

        transactionRunner.inTransaction {
            val candidates = changeRequestRepository.findDueForApplication(
                tenantId = tenantId,
                dueAt = dueAt,
                limit = command.limit,
            )
            val plans = candidates.associate { it.id to locks.discover(it) }
            val lockedTargets = locks.acquireTargets(tenantId, plans.values, skipBusy = true)
            val dueRequests = candidates.sortedBy { it.id.value.toString() }.mapNotNull { observed ->
                val plan = plans.getValue(observed.id)
                if (!lockedTargets.covers(plan)) return@mapNotNull null
                changeRequestRepository.claimForApplication(tenantId, observed.id, dueAt)?.also {
                    locks.requireSameIdentity(observed, it)
                }
            }.sortedWith(compareBy<LimitRuleChangeRequest> { it.createdAt }.thenBy { it.id.value.toString() })
            locks.acquireRules(tenantId, dueRequests.map { plans.getValue(it.id) })
            val appliedRequestIds = mutableListOf<LimitChangeRequestId>()
            val failedRequestIds = mutableListOf<LimitChangeRequestId>()

            dueRequests.forEach { request ->
                try {
                    transactionRunner.inSavepoint {
                        applyClaimed(request, dueAt, lockedTargets)
                    }
                    appliedRequestIds += request.id
                } catch (exception: ConflictException) {
                    markFailed(request, dueAt, exception)
                    failedRequestIds += request.id
                } catch (exception: NotFoundException) {
                    markFailed(request, dueAt, exception)
                    failedRequestIds += request.id
                } catch (exception: IllegalArgumentException) {
                    markFailed(request, dueAt, exception)
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

    private fun applyClaimed(
        request: LimitRuleChangeRequest,
        dueAt: Instant,
        lockedTargets: LockedLimitChangeTargets,
    ) {
        val currentRule = request.limitRuleId?.let {
            limitRuleRepository.findByIdForUpdate(request.tenantId, it)
        }
        val approvedRule = requireNotNull(request.approvedRule) {
            "approved rule is missing from change request: ${request.id.value}"
        }
        val candidate = LimitRuleSnapshotter.restoreRule(
            snapshot = approvedRule,
            createdAt = currentRule?.createdAt ?: request.createdAt,
            updatedAt = dueAt,
        )
        if (candidate.isActive && candidate.configurationId != null) {
            val configurationId = candidate.configurationId
            val hierarchy = limitConfigurationRepository.resolveHierarchy(request.tenantId, configurationId)
            candidate.validateAgainst(hierarchy.first(), hierarchy.drop(1))
        }
        mandateValidator.validate(candidate, dueAt)

        applicationWorkflow.applyInCurrentTransaction(
            request = request,
            candidate = candidate,
            actor = ChangeActor.System,
            applicationTime = dueAt,
            lockedTargets = lockedTargets,
        )
    }

    private fun markFailed(
        request: LimitRuleChangeRequest,
        dueAt: Instant,
        exception: RuntimeException,
    ) {
        val failureReason = exception.message ?: "scheduled limit change application failed"
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
}
