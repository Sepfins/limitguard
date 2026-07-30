package io.github.sepfins.limitguard.application.change

import io.github.sepfins.limitguard.api.LimitChangeService
import io.github.sepfins.limitguard.api.LimitRuleChangeType
import io.github.sepfins.limitguard.api.commands.ApplyDueLimitChangesCommand
import io.github.sepfins.limitguard.api.commands.ApproveLimitRuleChangeCommand
import io.github.sepfins.limitguard.api.commands.CancelLimitRuleChangeCommand
import io.github.sepfins.limitguard.api.commands.RejectLimitRuleChangeCommand
import io.github.sepfins.limitguard.api.commands.RequestLimitRuleChangeCommand
import io.github.sepfins.limitguard.api.errors.ConflictException
import io.github.sepfins.limitguard.api.errors.IdempotencyConflictException
import io.github.sepfins.limitguard.api.errors.NotFoundException
import io.github.sepfins.limitguard.api.errors.ValidationException
import io.github.sepfins.limitguard.api.results.ApplyDueChangesResult
import io.github.sepfins.limitguard.api.results.LimitChangeRequestResult
import io.github.sepfins.limitguard.domain.change.LimitRuleChangeRequest
import io.github.sepfins.limitguard.domain.common.LimitConfigurationId
import io.github.sepfins.limitguard.domain.common.TenantId
import io.github.sepfins.limitguard.port.ids.IdGenerator
import io.github.sepfins.limitguard.port.persistence.LimitChangeRequestRepository
import io.github.sepfins.limitguard.port.persistence.LimitConfigurationRepository
import io.github.sepfins.limitguard.port.persistence.LimitRuleChangeEventRepository
import io.github.sepfins.limitguard.port.time.ClockProvider
import io.github.sepfins.limitguard.port.transactions.TransactionRunner

internal class DefaultLimitChangeService(
    private val changeRequestRepository: LimitChangeRequestRepository,
    private val changeEventRepository: LimitRuleChangeEventRepository,
    private val limitConfigurationRepository: LimitConfigurationRepository,
    private val idGenerator: IdGenerator,
    private val clockProvider: ClockProvider,
    private val transactionRunner: TransactionRunner,
): LimitChangeService {
    private val limitRuleFactory = LimitRuleFactory(idGenerator)
    private val changeRequestFactory = LimitChangeRequestFactory(idGenerator)
    private val changeEventFactory = LimitChangeEventFactory(idGenerator)

    override fun requestLimitRuleChange(command: RequestLimitRuleChangeCommand): LimitChangeRequestResult = mapValidation {
        if (command.changeType != LimitRuleChangeType.CREATE) {
            throw ValidationException("requestLimitRuleChange currently supports CREATE changes only")
        }
        if (command.limitRuleId != null) {
            throw ValidationException("limitRuleId must be null for CREATE changes")
        }

        val now = clockProvider.now()
        val tenantId = TenantId.of(command.tenantId)

        val configurationId = command.requestedRule.configurationId
            ?.let { LimitConfigurationId.of(it) }
            ?: throw ValidationException("requestedRule.configurationId is required")

        val configuration = limitConfigurationRepository.findById(tenantId, configurationId)
            ?: throw NotFoundException("limit configuration not found: ${configurationId.value}")

        val requestedRule = limitRuleFactory.createForCreate(
            command = command,
            now = now
        )

        requestedRule.validateAgainst(configuration)
        if (!configuration.isEffectiveAt(requestedRule.effectiveWindow.startsAt)) {
            throw ValidationException(
                "limit configuration is not active at requested rule start: ${configuration.id.value}"
            )
        }

        val changeRequest = changeRequestFactory.create(
            command = command,
            requestedRule = requestedRule,
            now = now
        )

        val persistedRequest = transactionRunner.inTransaction {
            val existingRequest = changeRequest.idempotencyKey
                ?.let { changeRequestRepository.findByIdempotencyKey(tenantId, it) }

            if (existingRequest != null) {
                requireSameIdempotentRequest(existingRequest, changeRequest)
                existingRequest
            } else {
                val pendingForTarget = changeRequestRepository.findPendingForTarget(
                    tenantId = tenantId,
                    targetScopeHash = changeRequest.targetScopeHash,
                    at = now,
                )
                if (pendingForTarget.isNotEmpty()) {
                    throw ConflictException(
                        "pending limit change already exists for target scope: ${changeRequest.targetScopeHash.value}"
                    )
                }

                val insertedRequest = changeRequestRepository.insert(tenantId, changeRequest)
                changeEventRepository.insert(
                    tenantId,
                    changeEventFactory.requested(insertedRequest, now),
                )
                insertedRequest
            }
        }

        persistedRequest.toResult()
    }

    override fun approveLimitRuleChange(command: ApproveLimitRuleChangeCommand): LimitChangeRequestResult {
        TODO("Not yet implemented")
    }

    override fun rejectLimitRuleChange(command: RejectLimitRuleChangeCommand): LimitChangeRequestResult {
        TODO("Not yet implemented")
    }

    override fun cancelLimitRuleChange(command: CancelLimitRuleChangeCommand): LimitChangeRequestResult {
        TODO("Not yet implemented")
    }

    override fun applyDueLimitChanges(command: ApplyDueLimitChangesCommand): ApplyDueChangesResult {
        TODO("Not yet implemented")
    }

    private inline fun <T> mapValidation(block: () -> T): T =
        try {
            block()
        } catch (exception: IllegalArgumentException) {
            throw ValidationException(
                message = exception.message ?: "limit change command validation failed",
                cause = exception,
            )
    }

    private fun requireSameIdempotentRequest(
        existingRequest: LimitRuleChangeRequest,
        newRequest: LimitRuleChangeRequest,
    ) {
        val sameRequest =
            existingRequest.changeType == newRequest.changeType &&
                existingRequest.targetOwnerPartyProfileId == newRequest.targetOwnerPartyProfileId &&
                existingRequest.targetMandateId == newRequest.targetMandateId &&
                existingRequest.targetScopeHash == newRequest.targetScopeHash &&
                existingRequest.stableRequestedRule() == newRequest.stableRequestedRule() &&
                existingRequest.effectiveAt == newRequest.effectiveAt &&
                existingRequest.expiresAt == newRequest.expiresAt

        if (!sameRequest) {
            throw IdempotencyConflictException(
                "idempotency key already belongs to a different limit change request"
            )
        }
    }

    private fun LimitRuleChangeRequest.stableRequestedRule(): Map<String, Any?> =
        requestedRule.value.filterKeys { it !in GENERATED_RULE_SNAPSHOT_FIELDS }

    private companion object {
        val GENERATED_RULE_SNAPSHOT_FIELDS = setOf("id", "createdAt", "updatedAt")
    }
}
