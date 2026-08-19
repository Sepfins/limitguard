package io.github.sepfins.limitguard.application.change

import io.github.sepfins.limitguard.api.errors.ConflictException
import io.github.sepfins.limitguard.api.errors.NotFoundException
import io.github.sepfins.limitguard.domain.change.ChangeActor
import io.github.sepfins.limitguard.domain.change.LimitRuleChangeRequest
import io.github.sepfins.limitguard.domain.change.LimitRuleChangeStatus
import io.github.sepfins.limitguard.domain.change.LimitRuleChangeType
import io.github.sepfins.limitguard.domain.change.LimitRuleRevision
import io.github.sepfins.limitguard.domain.change.LimitRuleRevisionEventType
import io.github.sepfins.limitguard.domain.change.RevisionNumber
import io.github.sepfins.limitguard.domain.common.LimitRuleRevisionId
import io.github.sepfins.limitguard.domain.limit.LimitRule
import io.github.sepfins.limitguard.port.ids.IdGenerator
import io.github.sepfins.limitguard.port.persistence.LimitChangeRequestRepository
import io.github.sepfins.limitguard.port.persistence.LimitRuleChangeEventRepository
import io.github.sepfins.limitguard.port.persistence.LimitRuleRepository
import io.github.sepfins.limitguard.port.persistence.LimitRuleRevisionRepository
import java.time.Instant

internal class LimitChangeApplicationWorkflow(
    private val changeRequestRepository: LimitChangeRequestRepository,
    private val changeEventRepository: LimitRuleChangeEventRepository,
    private val limitRuleRepository: LimitRuleRepository,
    private val limitRuleRevisionRepository: LimitRuleRevisionRepository,
    private val changeEventFactory: LimitChangeEventFactory,
    private val idGenerator: IdGenerator,
) {
    fun applyInCurrentTransaction(
        request: LimitRuleChangeRequest,
        candidate: LimitRule,
        actor: ChangeActor,
        applicationTime: Instant,
    ): LimitRuleChangeRequest {
        if (request.status !in APPLICABLE_STATUSES) {
            throw ConflictException("limit change request is not approved: ${request.id.value}")
        }
        if (request.effectiveAt != null && request.effectiveAt > applicationTime) {
            throw ConflictException("limit change request is not due: ${request.id.value}")
        }
        if (request.expiresAt != null && request.expiresAt <= applicationTime) {
            throw ConflictException("limit change request has expired: ${request.id.value}")
        }
        requireCandidateMatchesRequest(request, candidate)

        val candidateAtApplication = candidate.copy(updatedAt = applicationTime)
        val persistedRule = when (request.changeType) {
            LimitRuleChangeType.CREATE -> limitRuleRepository.insert(request.tenantId, candidateAtApplication)
            else -> {
                val currentRule = limitRuleRepository.findById(
                    request.tenantId,
                    requireNotNull(request.limitRuleId),
                ) ?: throw NotFoundException("limit rule not found: ${request.limitRuleId.value}")
                val expectedVersion = (request.beforeRule?.value?.get("version") as? Number)?.toInt()
                    ?: throw ConflictException("limit change request is missing its expected rule version")
                if (currentRule.version.value != expectedVersion) {
                    throw ConflictException(
                        "limit rule version conflict: expected $expectedVersion, found ${currentRule.version.value}"
                    )
                }
                if (candidateAtApplication.version.value != currentRule.version.next().value) {
                    throw ConflictException("approved rule has an invalid version: ${candidateAtApplication.version.value}")
                }
                limitRuleRepository.update(request.tenantId, candidateAtApplication)
            }
        }

        limitRuleRevisionRepository.insert(
            request.tenantId,
            LimitRuleRevision(
                id = LimitRuleRevisionId.of(idGenerator.newId()),
                tenantId = request.tenantId,
                limitRuleId = persistedRule.id,
                revisionNumber = RevisionNumber.of(persistedRule.version.value),
                revisionEventType = request.changeType.toRevisionEventType(),
                actor = actor,
                changeRequestId = request.id,
                beforeRule = request.beforeRule,
                afterRule = LimitRuleSnapshotter.ruleSnapshot(persistedRule),
                effectiveAt = applicationTime,
                createdAt = applicationTime,
            ),
        )

        val applied = request.markApplied(persistedRule.id, applicationTime)
        val persistedApplied = changeRequestRepository.update(request.tenantId, applied)
        changeEventRepository.insert(
            request.tenantId,
            changeEventFactory.applied(request, persistedApplied, applicationTime, actor),
        )
        return persistedApplied
    }

    private fun requireCandidateMatchesRequest(
        request: LimitRuleChangeRequest,
        candidate: LimitRule,
    ) {
        if (candidate.tenantId != request.tenantId ||
            candidate.ownerPartyProfileId != request.targetOwnerPartyProfileId ||
            (request.limitRuleId != null && candidate.id != request.limitRuleId)
        ) {
            throw ConflictException("approved rule identity does not match change request: ${request.id.value}")
        }
        if (LimitRuleSnapshotter.targetScopeSnapshot(candidate.toTargetScope()) != request.targetScope) {
            throw ConflictException("approved rule scope does not match change request: ${request.id.value}")
        }
    }

    private fun LimitRuleChangeType.toRevisionEventType(): LimitRuleRevisionEventType =
        when (this) {
            LimitRuleChangeType.CREATE -> LimitRuleRevisionEventType.CREATED
            LimitRuleChangeType.UPDATE -> LimitRuleRevisionEventType.UPDATED
            LimitRuleChangeType.DEACTIVATE -> LimitRuleRevisionEventType.DEACTIVATED
            LimitRuleChangeType.REACTIVATE -> LimitRuleRevisionEventType.REACTIVATED
            LimitRuleChangeType.DELETE -> LimitRuleRevisionEventType.DELETED
        }

    private companion object {
        val APPLICABLE_STATUSES = setOf(
            LimitRuleChangeStatus.APPROVED,
            LimitRuleChangeStatus.PENDING_EFFECTIVE_DATE,
        )
    }
}
