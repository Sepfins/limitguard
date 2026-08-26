package io.github.sepfins.limitguard.application.change

import io.github.sepfins.limitguard.api.LimitChangeActorType
import io.github.sepfins.limitguard.api.LimitChannelScope
import io.github.sepfins.limitguard.api.LimitKind
import io.github.sepfins.limitguard.api.LimitMandateScope
import io.github.sepfins.limitguard.api.LimitRuleChangeStatus
import io.github.sepfins.limitguard.api.LimitRuleChangeType
import io.github.sepfins.limitguard.api.LimitUsageConsumptionMode
import io.github.sepfins.limitguard.api.commands.LimitChangeActorCommand
import io.github.sepfins.limitguard.api.commands.LimitRuleDraft
import io.github.sepfins.limitguard.api.commands.ApplyDueLimitChangesCommand
import io.github.sepfins.limitguard.api.commands.ApproveLimitRuleChangeCommand
import io.github.sepfins.limitguard.api.commands.CancelLimitRuleChangeCommand
import io.github.sepfins.limitguard.api.commands.RejectLimitRuleChangeCommand
import io.github.sepfins.limitguard.api.commands.RequestLimitRuleChangeCommand
import io.github.sepfins.limitguard.api.errors.ConflictException
import io.github.sepfins.limitguard.api.errors.IdempotencyConflictException
import io.github.sepfins.limitguard.api.errors.NotFoundException
import io.github.sepfins.limitguard.api.errors.ValidationException
import io.github.sepfins.limitguard.domain.change.ChangeActor
import io.github.sepfins.limitguard.domain.change.LimitChangePolicy
import io.github.sepfins.limitguard.domain.change.LimitChangePolicyCode
import io.github.sepfins.limitguard.domain.change.LimitChangePolicyDecision
import io.github.sepfins.limitguard.domain.change.LimitChangePolicyEngine
import io.github.sepfins.limitguard.domain.change.LimitChangePolicyEvaluationOutcome
import io.github.sepfins.limitguard.domain.change.LimitChangePolicyName
import io.github.sepfins.limitguard.domain.change.LimitChangePolicyPhase
import io.github.sepfins.limitguard.domain.change.LimitRuleChangeEventType
import io.github.sepfins.limitguard.domain.change.LimitRuleChangeRequest
import io.github.sepfins.limitguard.domain.change.LimitRuleRevisionEventType
import io.github.sepfins.limitguard.domain.change.LimitRuleChangeStatus as DomainLimitRuleChangeStatus
import io.github.sepfins.limitguard.domain.common.BankWorkerId
import io.github.sepfins.limitguard.domain.common.CurrencyCode
import io.github.sepfins.limitguard.domain.common.EffectiveWindow
import io.github.sepfins.limitguard.domain.common.LimitConfigurationId
import io.github.sepfins.limitguard.domain.common.LimitChangePolicyId
import io.github.sepfins.limitguard.domain.common.LimitChangeRequestId
import io.github.sepfins.limitguard.domain.common.LimitRuleId
import io.github.sepfins.limitguard.domain.common.MandateId
import io.github.sepfins.limitguard.domain.common.Metadata
import io.github.sepfins.limitguard.domain.common.Money
import io.github.sepfins.limitguard.domain.common.PartyProfileId
import io.github.sepfins.limitguard.domain.common.TenantId
import io.github.sepfins.limitguard.domain.common.Version
import io.github.sepfins.limitguard.domain.limit.LimitChannelScope as DomainLimitChannelScope
import io.github.sepfins.limitguard.domain.limit.LimitConfiguration
import io.github.sepfins.limitguard.domain.limit.LimitConfigurationCode
import io.github.sepfins.limitguard.domain.limit.LimitConfigurationFundingScope
import io.github.sepfins.limitguard.domain.limit.LimitConfigurationName
import io.github.sepfins.limitguard.domain.limit.LimitKind as DomainLimitKind
import io.github.sepfins.limitguard.domain.limit.LimitOperationScope
import io.github.sepfins.limitguard.domain.limit.LimitPriority
import io.github.sepfins.limitguard.domain.limit.LimitRule
import io.github.sepfins.limitguard.domain.limit.LimitRuleName
import io.github.sepfins.limitguard.domain.limit.LimitUsageConsumptionMode as DomainLimitUsageConsumptionMode
import io.github.sepfins.limitguard.domain.funding.FundingSourceScope
import io.github.sepfins.limitguard.domain.mandate.Mandate
import io.github.sepfins.limitguard.domain.mandate.MandateName
import io.github.sepfins.limitguard.domain.mandate.MandateScope
import io.github.sepfins.limitguard.domain.policy.PolicyEvaluationContext
import io.github.sepfins.limitguard.domain.policy.PolicyEvaluationResult
import io.github.sepfins.limitguard.port.policy.PolicyEngine
import io.github.sepfins.limitguard.support.DirectTransactionRunner
import io.github.sepfins.limitguard.support.FixedClockProvider
import io.github.sepfins.limitguard.support.SequentialIdGenerator
import io.github.sepfins.limitguard.support.persistence.InMemoryLimitguardRepositories
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class DefaultLimitChangeServiceTest {
    private val now = Instant.parse("2026-06-26T12:00:00Z")
    private val tenantId = TenantId.of(UUID.fromString("00000000-0000-0000-0000-000000000001"))
    private val ownerId = UUID.fromString("00000000-0000-0000-0000-000000000002")
    private val requesterId = UUID.fromString("00000000-0000-0000-0000-000000000003")
    private val channelId = UUID.fromString("00000000-0000-0000-0000-000000000004")
    private val configurationId = LimitConfigurationId.of(UUID.fromString("00000000-0000-0000-0000-000000000005"))
    private val existingRuleId = LimitRuleId.of(UUID.fromString("00000000-0000-0000-0000-000000000006"))
    private val pendingRuleId = LimitRuleId.of(UUID.fromString("00000000-0000-0000-0000-000000000007"))
    private val pendingRequestId = LimitChangeRequestId.of(UUID.fromString("00000000-0000-0000-0000-000000000008"))
    private val approverId = UUID.fromString("00000000-0000-0000-0000-000000000009")
    private val mandateId = MandateId.of(UUID.fromString("00000000-0000-0000-0000-000000000010"))
    private val authorizationPolicyId =
        LimitChangePolicyId.of(UUID.fromString("00000000-0000-0000-0000-000000000011"))
    private val reviewPolicyId =
        LimitChangePolicyId.of(UUID.fromString("00000000-0000-0000-0000-000000000012"))

    @Test
    fun `requestLimitRuleChange applies create request with complete audit history`() {
        val repositories = repositoriesWithConfiguration()
        val service = limitChangeService(repositories)

        val result = service.requestLimitRuleChange(command(idempotencyKey = "request-1"))

        assertEquals(tenantId.value, result.tenantId)
        assertEquals(LimitRuleChangeType.CREATE, result.changeType)
        assertEquals(LimitRuleChangeStatus.APPLIED, result.status)
        assertEquals(ownerId, result.targetOwnerPartyProfileId)
        assertEquals(null, result.targetMandateId)
        assertNotNull(result.targetScopeHash)
        val limitRuleId = assertNotNull(result.limitRuleId)
        assertEquals(now, result.createdAt)
        assertEquals(now, result.updatedAt)

        val insertedRequest = repositories.limitChangeRequests.findById(
            tenantId,
            LimitChangeRequestId.of(result.id),
        )
        assertNotNull(insertedRequest)
        assertEquals("500.0000", insertedRequest.requestedRule.value["amount"])
        assertEquals("PER_TRANSACTION", insertedRequest.targetScope.value["kind"])
        assertEquals(limitRuleId, insertedRequest.limitRuleId?.value)

        val events = repositories.limitRuleChangeEvents.findByChangeRequest(tenantId, insertedRequest.id)
        assertEquals(
            listOf(
                LimitRuleChangeEventType.REQUESTED,
                LimitRuleChangeEventType.POLICY_EVALUATED,
                LimitRuleChangeEventType.APPROVED,
                LimitRuleChangeEventType.APPLIED,
            ),
            events.map { it.eventType },
        )

        val rule = assertNotNull(
            repositories.limitRules.findById(tenantId, LimitRuleId.of(limitRuleId))
        )
        assertEquals(1, rule.version.value)
        assertEquals(true, rule.isActive)

        val revisions = repositories.limitRuleRevisions.findByChangeRequest(tenantId, insertedRequest.id)
        assertEquals(1, revisions.size)
        assertEquals(rule.id, revisions.single().limitRuleId)
        assertEquals(rule.version.value, revisions.single().revisionNumber.value)

        val evaluation = repositories.limitChangePolicyEvaluations
            .findByChangeRequest(tenantId, insertedRequest.id)
            .single()
        assertEquals(authorizationPolicyId, evaluation.policyId)
        assertEquals(LimitChangePolicyDecision.ALLOW, evaluation.decision)
        assertEquals(LimitChangePolicyEvaluationOutcome.APPLIED, evaluation.outcome)
        assertEquals("AUTHORIZE_LIMIT_CHANGE", evaluation.policySnapshot.value["code"])
        assertEquals(
            result.id.toString(),
            (evaluation.inputContext.value["changeRequest"] as Map<*, *>)["id"],
        )
        assertEquals(authorizationPolicyId, events[1].policyId)
        assertEquals(authorizationPolicyId, events[2].policyId)
    }

    @Test
    fun `require approval policy leaves public request open for manual approval`() {
        val repositories = repositoriesWithConfiguration(LimitChangePolicyDecision.REQUIRE_APPROVAL)
        val service = limitChangeService(repositories)

        val requested = service.requestLimitRuleChange(command(idempotencyKey = "manual-approval"))

        assertEquals(LimitRuleChangeStatus.REQUESTED, requested.status)
        assertEquals(null, requested.limitRuleId)
        assertEquals(
            listOf(
                LimitRuleChangeEventType.REQUESTED,
                LimitRuleChangeEventType.POLICY_EVALUATED,
            ),
            repositories.limitRuleChangeEvents
                .findByChangeRequest(tenantId, LimitChangeRequestId.of(requested.id))
                .map { it.eventType },
        )

        val approved = service.approveLimitRuleChange(
            ApproveLimitRuleChangeCommand(
                tenantId = tenantId.value,
                changeRequestId = requested.id,
                approvedBy = LimitChangeActorCommand(
                    actorType = LimitChangeActorType.BANK_WORKER,
                    bankWorkerId = approverId,
                ),
                decisionReason = "Approved after review",
            )
        )

        assertEquals(LimitRuleChangeStatus.APPLIED, approved.status)
        assertNotNull(approved.limitRuleId)
        assertEquals(
            listOf(
                LimitRuleChangeEventType.REQUESTED,
                LimitRuleChangeEventType.POLICY_EVALUATED,
                LimitRuleChangeEventType.APPROVED,
                LimitRuleChangeEventType.APPLIED,
            ),
            repositories.limitRuleChangeEvents
                .findByChangeRequest(tenantId, LimitChangeRequestId.of(requested.id))
                .map { it.eventType },
        )
    }

    @Test
    fun `require approval policy leaves public request open for manual rejection`() {
        val repositories = repositoriesWithConfiguration(LimitChangePolicyDecision.REQUIRE_APPROVAL)
        val service = limitChangeService(repositories)
        val requested = service.requestLimitRuleChange(command(idempotencyKey = "manual-rejection"))

        val rejected = service.rejectLimitRuleChange(
            RejectLimitRuleChangeCommand(
                tenantId = tenantId.value,
                changeRequestId = requested.id,
                rejectedBy = LimitChangeActorCommand(LimitChangeActorType.SYSTEM),
                decisionReason = "Rejected after review",
            )
        )

        assertEquals(LimitRuleChangeStatus.REJECTED, rejected.status)
        assertEquals(null, rejected.limitRuleId)
        assertEquals(
            listOf(
                LimitRuleChangeEventType.REQUESTED,
                LimitRuleChangeEventType.POLICY_EVALUATED,
                LimitRuleChangeEventType.REJECTED,
            ),
            repositories.limitRuleChangeEvents
                .findByChangeRequest(tenantId, LimitChangeRequestId.of(requested.id))
                .map { it.eventType },
        )
    }

    @Test
    fun `first matching policy by priority controls authorization`() {
        val repositories = repositoriesWithConfiguration(LimitChangePolicyDecision.ALLOW)
        repositories.limitChangePolicies.insert(
            tenantId,
            authorizationPolicy(
                decision = LimitChangePolicyDecision.REQUIRE_APPROVAL,
                id = reviewPolicyId,
                code = "REVIEW_LIMIT_CHANGE",
                priority = 10,
            ),
        )
        val service = limitChangeService(repositories)

        val result = service.requestLimitRuleChange(command())

        assertEquals(LimitRuleChangeStatus.REQUESTED, result.status)
        val evaluations = repositories.limitChangePolicyEvaluations
            .findByChangeRequest(tenantId, LimitChangeRequestId.of(result.id))
        assertEquals(listOf(reviewPolicyId), evaluations.map { it.policyId })
    }

    @Test
    fun `deny policy rejects the public request without creating a rule`() {
        val repositories = repositoriesWithConfiguration(LimitChangePolicyDecision.DENY)
        val service = limitChangeService(repositories)

        val result = service.requestLimitRuleChange(command(idempotencyKey = "policy-denial"))

        assertEquals(LimitRuleChangeStatus.REJECTED, result.status)
        assertEquals("Change denied by test policy", result.decisionReason)
        assertEquals(null, result.limitRuleId)
        assertEquals(emptyList(), repositories.limitRuleRevisions.findByChangeRequest(
            tenantId,
            LimitChangeRequestId.of(result.id),
        ))
        val evaluation = repositories.limitChangePolicyEvaluations
            .findByChangeRequest(tenantId, LimitChangeRequestId.of(result.id))
            .single()
        assertEquals(LimitChangePolicyDecision.DENY, evaluation.decision)
        assertEquals(LimitChangePolicyEvaluationOutcome.DENIED, evaluation.outcome)
        assertEquals(
            listOf(
                LimitRuleChangeEventType.REQUESTED,
                LimitRuleChangeEventType.POLICY_EVALUATED,
                LimitRuleChangeEventType.REJECTED,
            ),
            repositories.limitRuleChangeEvents
                .findByChangeRequest(tenantId, LimitChangeRequestId.of(result.id))
                .map { it.eventType },
        )
    }

    @Test
    fun `request requires manual approval when no authorization policy applies`() {
        val repositories = repositoriesWithConfiguration(authorizationDecision = null)
        val service = limitChangeService(repositories)

        val result = service.requestLimitRuleChange(command())

        assertEquals(LimitRuleChangeStatus.REQUESTED, result.status)
        assertEquals(
            listOf(LimitRuleChangeEventType.REQUESTED),
            repositories.limitRuleChangeEvents
                .findByChangeRequest(tenantId, LimitChangeRequestId.of(result.id))
                .map { it.eventType },
        )
        assertEquals(
            emptyList(),
            repositories.limitChangePolicyEvaluations
                .findByChangeRequest(tenantId, LimitChangeRequestId.of(result.id)),
        )
    }

    @Test
    fun `missing policy engine fails closed with an auditable request`() {
        val repositories = repositoriesWithConfiguration()
        val service = limitChangeService(repositories, policyEngines = emptyList())

        val result = service.requestLimitRuleChange(command())

        assertEquals(LimitRuleChangeStatus.FAILED, result.status)
        assertEquals(null, result.limitRuleId)
        val evaluation = repositories.limitChangePolicyEvaluations
            .findByChangeRequest(tenantId, LimitChangeRequestId.of(result.id))
            .single()
        assertEquals(LimitChangePolicyEvaluationOutcome.ERROR, evaluation.outcome)
        assertEquals(
            listOf(
                LimitRuleChangeEventType.REQUESTED,
                LimitRuleChangeEventType.POLICY_EVALUATED,
                LimitRuleChangeEventType.FAILED,
            ),
            repositories.limitRuleChangeEvents
                .findByChangeRequest(tenantId, LimitChangeRequestId.of(result.id))
                .map { it.eventType },
        )
    }

    @Test
    fun `mandate-specific create requires an applicable mandate owned by the target`() {
        val repositories = repositoriesWithConfiguration()
        val service = limitChangeService(repositories)
        val mandateCommand = command().copy(
            requestedRule = draft().copy(
                mandateScope = LimitMandateScope.MANDATE,
                mandateId = mandateId.value,
            ),
            targetMandateId = mandateId.value,
        )

        assertFailsWith<NotFoundException> {
            service.requestLimitRuleChange(mandateCommand)
        }

        val wrongOwnerMandate = mandate(
            owner = PartyProfileId.of(requesterId),
            assignee = PartyProfileId.of(ownerId),
        )
        repositories.mandates.insert(tenantId, wrongOwnerMandate)
        assertFailsWith<ConflictException> {
            service.requestLimitRuleChange(mandateCommand)
        }

        repositories.mandates.update(
            tenantId,
            mandate(
                effectiveWindow = EffectiveWindow(now.minusSeconds(120), now.minusSeconds(60)),
            ),
        )
        assertFailsWith<ConflictException> {
            service.requestLimitRuleChange(mandateCommand)
        }

        repositories.mandates.update(tenantId, mandate())
        val result = service.requestLimitRuleChange(mandateCommand)

        assertEquals(LimitRuleChangeStatus.APPLIED, result.status)
        assertEquals(mandateId.value, result.targetMandateId)
        val rule = assertNotNull(
            repositories.limitRules.findById(tenantId, LimitRuleId.of(requireNotNull(result.limitRuleId)))
        )
        assertEquals(MandateScope.MandateSpecific(mandateId), rule.mandateScope)
    }

    @Test
    fun `direct owner create does not require an explicit mandate`() {
        val repositories = repositoriesWithConfiguration()
        val service = limitChangeService(repositories)
        val result = service.requestLimitRuleChange(
            command().copy(
                requestedBy = LimitChangeActorCommand(
                    actorType = LimitChangeActorType.PARTY_PROFILE,
                    partyProfileId = ownerId,
                )
            )
        )

        assertEquals(LimitRuleChangeStatus.APPLIED, result.status)
        assertEquals(null, result.targetMandateId)
        assertEquals(emptyList(), repositories.mandates.findActiveForOwner(tenantId, PartyProfileId.of(ownerId), now))
    }

    @Test
    fun `same idempotency key returns existing request for the same command`() {
        val repositories = repositoriesWithConfiguration()
        val clock = FixedClockProvider(now)
        val service = limitChangeService(repositories, clock)

        val first = service.requestLimitRuleChange(command(idempotencyKey = "request-1"))
        clock.advanceBy(Duration.ofMinutes(5))
        val second = service.requestLimitRuleChange(command(idempotencyKey = "request-1"))

        assertEquals(first.id, second.id)
        assertEquals(4, repositories.limitRuleChangeEvents.findByChangeRequest(
            tenantId,
            LimitChangeRequestId.of(first.id),
        ).size)
        assertEquals(1, repositories.limitRules.findActiveForOwner(tenantId, PartyProfileId.of(ownerId), now).size)
        assertEquals(1, repositories.limitRuleRevisions.findByChangeRequest(
            tenantId,
            LimitChangeRequestId.of(first.id),
        ).size)
        assertEquals(1, repositories.limitChangePolicyEvaluations.findByChangeRequest(
            tenantId,
            LimitChangeRequestId.of(first.id),
        ).size)
    }

    @Test
    fun `same idempotency key with different request fails`() {
        val repositories = repositoriesWithConfiguration()
        val service = limitChangeService(repositories)

        service.requestLimitRuleChange(command(idempotencyKey = "request-1"))

        assertFailsWith<IdempotencyConflictException> {
            service.requestLimitRuleChange(command(idempotencyKey = "request-1", amount = "600.00"))
        }
    }

    @Test
    fun `second create request for an active target scope fails without idempotency key`() {
        val repositories = repositoriesWithConfiguration()
        val service = limitChangeService(repositories)

        service.requestLimitRuleChange(command(name = "Daily ATM limit"))

        assertFailsWith<ConflictException> {
            service.requestLimitRuleChange(command(name = "Another name, same target scope"))
        }
    }

    @Test
    fun `requestLimitRuleChange updates an existing rule`() {
        val repositories = repositoriesWithConfiguration()
        val currentRule = insertRule(repositories)
        val service = limitChangeService(repositories)

        val result = service.requestLimitRuleChange(
            commandForExistingRule(
                changeType = LimitRuleChangeType.UPDATE,
                currentRule = currentRule,
                requestedRule = draft(name = "Updated ATM limit", amount = "650.00"),
                idempotencyKey = "update-1",
            )
        )

        assertEquals(LimitRuleChangeStatus.APPLIED, result.status)
        assertEquals(currentRule.id.value, result.limitRuleId)
        val updated = assertNotNull(repositories.limitRules.findById(tenantId, currentRule.id))
        assertEquals("Updated ATM limit", updated.name.value)
        assertEquals(BigDecimal("650.0000"), updated.amount.amount)
        assertEquals(2, updated.version.value)
        val revision = repositories.limitRuleRevisions
            .findByChangeRequest(tenantId, LimitChangeRequestId.of(result.id))
            .single()
        assertEquals(LimitRuleRevisionEventType.UPDATED, revision.revisionEventType)
        assertEquals(1, revision.beforeRule?.value?.get("version"))
        assertEquals(2, revision.afterRule?.value?.get("version"))

        val replay = service.requestLimitRuleChange(
            commandForExistingRule(
                changeType = LimitRuleChangeType.UPDATE,
                currentRule = currentRule,
                requestedRule = draft(name = "Updated ATM limit", amount = "650.00"),
                idempotencyKey = "update-1",
            )
        )
        assertEquals(result.id, replay.id)
        assertEquals(1, repositories.limitRuleRevisions.findByChangeRequest(
            tenantId,
            LimitChangeRequestId.of(result.id),
        ).size)
    }

    @Test
    fun `requestLimitRuleChange deactivates an existing rule`() {
        val repositories = repositoriesWithConfiguration()
        val currentRule = insertRule(repositories)
        val service = limitChangeService(repositories)

        val result = service.requestLimitRuleChange(
            commandForExistingRule(LimitRuleChangeType.DEACTIVATE, currentRule)
        )

        assertEquals(LimitRuleChangeStatus.APPLIED, result.status)
        val deactivated = assertNotNull(repositories.limitRules.findById(tenantId, currentRule.id))
        assertEquals(false, deactivated.isActive)
        assertEquals(2, deactivated.version.value)
        assertEquals(
            LimitRuleRevisionEventType.DEACTIVATED,
            repositories.limitRuleRevisions
                .findByChangeRequest(tenantId, LimitChangeRequestId.of(result.id))
                .single()
                .revisionEventType,
        )
    }

    @Test
    fun `requestLimitRuleChange reactivates an inactive rule`() {
        val repositories = repositoriesWithConfiguration()
        val currentRule = insertRule(repositories, isActive = false)
        val service = limitChangeService(repositories)

        val result = service.requestLimitRuleChange(
            commandForExistingRule(LimitRuleChangeType.REACTIVATE, currentRule)
        )

        assertEquals(LimitRuleChangeStatus.APPLIED, result.status)
        val reactivated = assertNotNull(repositories.limitRules.findById(tenantId, currentRule.id))
        assertEquals(true, reactivated.isActive)
        assertEquals(2, reactivated.version.value)
        assertEquals(
            LimitRuleRevisionEventType.REACTIVATED,
            repositories.limitRuleRevisions
                .findByChangeRequest(tenantId, LimitChangeRequestId.of(result.id))
                .single()
                .revisionEventType,
        )
    }

    @Test
    fun `reactivating an expired rule requires and accepts a replacement draft`() {
        val repositories = repositoriesWithConfiguration()
        val currentRule = insertRule(
            repositories = repositories,
            isActive = false,
            endsAt = now.minusSeconds(1),
        )
        val service = limitChangeService(repositories)

        assertFailsWith<ValidationException> {
            service.requestLimitRuleChange(
                commandForExistingRule(LimitRuleChangeType.REACTIVATE, currentRule)
            )
        }

        val result = service.requestLimitRuleChange(
            commandForExistingRule(
                changeType = LimitRuleChangeType.REACTIVATE,
                currentRule = currentRule,
                requestedRule = draft(amount = "550.00").copy(startsAt = now),
            )
        )

        assertEquals(LimitRuleChangeStatus.APPLIED, result.status)
        val reactivated = assertNotNull(repositories.limitRules.findById(tenantId, currentRule.id))
        assertEquals(true, reactivated.isActive)
        assertEquals(BigDecimal("550.0000"), reactivated.amount.amount)
        assertEquals(now, reactivated.effectiveWindow.startsAt)
    }

    @Test
    fun `requestLimitRuleChange logically deletes a rule and prevents later changes`() {
        val repositories = repositoriesWithConfiguration()
        val currentRule = insertRule(repositories)
        val service = limitChangeService(repositories)

        val result = service.requestLimitRuleChange(
            commandForExistingRule(LimitRuleChangeType.DELETE, currentRule)
        )

        assertEquals(LimitRuleChangeStatus.APPLIED, result.status)
        val deleted = assertNotNull(repositories.limitRules.findById(tenantId, currentRule.id))
        assertEquals(false, deleted.isActive)
        assertEquals(2, deleted.version.value)
        assertEquals(
            LimitRuleRevisionEventType.DELETED,
            repositories.limitRuleRevisions
                .findByChangeRequest(tenantId, LimitChangeRequestId.of(result.id))
                .single()
                .revisionEventType,
        )

        assertFailsWith<ConflictException> {
            service.requestLimitRuleChange(
                commandForExistingRule(
                    changeType = LimitRuleChangeType.REACTIVATE,
                    currentRule = deleted,
                )
            )
        }
    }

    @Test
    fun `future-effective request is approved and scheduled without mutating the rule`() {
        val repositories = repositoriesWithConfiguration()
        val currentRule = insertRule(repositories)
        val service = limitChangeService(repositories)

        val result = service.requestLimitRuleChange(
            commandForExistingRule(
                changeType = LimitRuleChangeType.UPDATE,
                currentRule = currentRule,
                requestedRule = draft(amount = "700.00"),
                effectiveAt = now.plusSeconds(3600),
            )
        )

        assertEquals(LimitRuleChangeStatus.PENDING_EFFECTIVE_DATE, result.status)
        assertEquals(currentRule, repositories.limitRules.findById(tenantId, currentRule.id))
        assertEquals(
            listOf(
                LimitRuleChangeEventType.REQUESTED,
                LimitRuleChangeEventType.POLICY_EVALUATED,
                LimitRuleChangeEventType.APPROVED,
                LimitRuleChangeEventType.SCHEDULED,
            ),
            repositories.limitRuleChangeEvents
                .findByChangeRequest(tenantId, LimitChangeRequestId.of(result.id))
                .map { it.eventType },
        )
        assertEquals(
            emptyList(),
            repositories.limitRuleRevisions.findByChangeRequest(tenantId, LimitChangeRequestId.of(result.id)),
        )
    }

    @Test
    fun `non-create request requires the current rule version`() {
        val repositories = repositoriesWithConfiguration()
        val currentRule = insertRule(repositories)
        val service = limitChangeService(repositories)

        val missingVersion = commandForExistingRule(
            changeType = LimitRuleChangeType.UPDATE,
            currentRule = currentRule,
        ).copy(expectedVersion = null)
        assertFailsWith<ValidationException> {
            service.requestLimitRuleChange(missingVersion)
        }

        val staleVersion = commandForExistingRule(
            changeType = LimitRuleChangeType.DEACTIVATE,
            currentRule = currentRule,
        ).copy(expectedVersion = 2)
        assertFailsWith<ConflictException> {
            service.requestLimitRuleChange(staleVersion)
        }
    }

    @Test
    fun `approveLimitRuleChange applies a requested create with approver audit`() {
        val repositories = repositoriesWithConfiguration()
        val request = insertRequestedCreate(repositories)
        val service = limitChangeService(repositories)
        val approver = LimitChangeActorCommand(
            actorType = LimitChangeActorType.BANK_WORKER,
            bankWorkerId = approverId,
        )

        val result = service.approveLimitRuleChange(
            ApproveLimitRuleChangeCommand(
                tenantId = tenantId.value,
                changeRequestId = request.id.value,
                approvedBy = approver,
                decisionReason = "Approved by branch supervisor",
                metadata = mapOf("caseId" to "approval-1"),
            )
        )

        assertEquals(LimitRuleChangeStatus.APPLIED, result.status)
        assertEquals(pendingRuleId.value, result.limitRuleId)
        assertEquals("Approved by branch supervisor", result.decisionReason)
        val persistedRequest = assertNotNull(repositories.limitChangeRequests.findById(tenantId, request.id))
        assertEquals(request.requestedRule, persistedRequest.approvedRule)

        val events = repositories.limitRuleChangeEvents.findByChangeRequest(tenantId, request.id)
        assertEquals(
            listOf(
                LimitRuleChangeEventType.REQUESTED,
                LimitRuleChangeEventType.APPROVED,
                LimitRuleChangeEventType.APPLIED,
            ),
            events.map { it.eventType },
        )
        val approvalEvent = events[1]
        assertEquals(ChangeActor.BankWorkerActor(BankWorkerId.of(approverId)), approvalEvent.actor)
        assertEquals(mapOf("caseId" to "approval-1"), approvalEvent.eventPayload.value)

        val revision = repositories.limitRuleRevisions.findByChangeRequest(tenantId, request.id).single()
        assertEquals(ChangeActor.BankWorkerActor(BankWorkerId.of(approverId)), revision.actor)
        assertEquals(pendingRuleId, revision.limitRuleId)
    }

    @Test
    fun `approveLimitRuleChange applies an adjusted approved rule`() {
        val repositories = repositoriesWithConfiguration()
        val request = insertRequestedCreate(repositories)
        val service = limitChangeService(repositories)

        val result = service.approveLimitRuleChange(
            ApproveLimitRuleChangeCommand(
                tenantId = tenantId.value,
                changeRequestId = request.id.value,
                approvedBy = LimitChangeActorCommand(LimitChangeActorType.SYSTEM),
                approvedRule = draft(amount = "750.00"),
            )
        )

        assertEquals(LimitRuleChangeStatus.APPLIED, result.status)
        val persistedRequest = assertNotNull(repositories.limitChangeRequests.findById(tenantId, request.id))
        assertEquals("500.0000", persistedRequest.requestedRule.value["amount"])
        assertEquals("750.0000", persistedRequest.approvedRule?.value?.get("amount"))
        val rule = assertNotNull(repositories.limitRules.findById(tenantId, pendingRuleId))
        assertEquals(BigDecimal("750.0000"), rule.amount.amount)
    }

    @Test
    fun `approveLimitRuleChange validates an adjusted mandate scope`() {
        val repositories = repositoriesWithConfiguration()
        val request = insertRequestedCreate(repositories)
        val service = limitChangeService(repositories)

        assertFailsWith<NotFoundException> {
            service.approveLimitRuleChange(
                ApproveLimitRuleChangeCommand(
                    tenantId = tenantId.value,
                    changeRequestId = request.id.value,
                    approvedBy = LimitChangeActorCommand(LimitChangeActorType.SYSTEM),
                    approvedRule = draft().copy(
                        mandateScope = LimitMandateScope.MANDATE,
                        mandateId = mandateId.value,
                    ),
                )
            )
        }

        val unchanged = assertNotNull(repositories.limitChangeRequests.findById(tenantId, request.id))
        assertEquals(DomainLimitRuleChangeStatus.REQUESTED, unchanged.status)
        assertEquals(null, repositories.limitRules.findById(tenantId, pendingRuleId))
    }

    @Test
    fun `approveLimitRuleChange schedules a future request without applying it`() {
        val repositories = repositoriesWithConfiguration()
        val request = insertRequestedCreate(
            repositories,
            command().copy(effectiveAt = now.plusSeconds(3600)),
        )
        val service = limitChangeService(repositories)

        val result = service.approveLimitRuleChange(
            ApproveLimitRuleChangeCommand(
                tenantId = tenantId.value,
                changeRequestId = request.id.value,
                approvedBy = LimitChangeActorCommand(LimitChangeActorType.SYSTEM),
            )
        )

        assertEquals(LimitRuleChangeStatus.PENDING_EFFECTIVE_DATE, result.status)
        assertEquals(null, repositories.limitRules.findById(tenantId, pendingRuleId))
        assertEquals(emptyList(), repositories.limitRuleRevisions.findByChangeRequest(tenantId, request.id))
        assertEquals(
            listOf(
                LimitRuleChangeEventType.REQUESTED,
                LimitRuleChangeEventType.APPROVED,
                LimitRuleChangeEventType.SCHEDULED,
            ),
            repositories.limitRuleChangeEvents.findByChangeRequest(tenantId, request.id).map { it.eventType },
        )
    }

    @Test
    fun `approveLimitRuleChange rejects a request that is no longer awaiting approval`() {
        val repositories = repositoriesWithConfiguration()
        val service = limitChangeService(repositories)
        val applied = service.requestLimitRuleChange(command())

        assertFailsWith<ConflictException> {
            service.approveLimitRuleChange(
                ApproveLimitRuleChangeCommand(
                    tenantId = tenantId.value,
                    changeRequestId = applied.id,
                    approvedBy = LimitChangeActorCommand(LimitChangeActorType.SYSTEM),
                )
            )
        }
    }

    @Test
    fun `rejectLimitRuleChange rejects a requested change with audit details`() {
        val repositories = repositoriesWithConfiguration()
        val request = insertRequestedCreate(repositories)
        val service = limitChangeService(repositories)
        val rejector = LimitChangeActorCommand(
            actorType = LimitChangeActorType.BANK_WORKER,
            bankWorkerId = approverId,
        )

        val result = service.rejectLimitRuleChange(
            RejectLimitRuleChangeCommand(
                tenantId = tenantId.value,
                changeRequestId = request.id.value,
                rejectedBy = rejector,
                decisionReason = "Requested amount exceeds branch policy",
                metadata = mapOf("caseId" to "rejection-1"),
            )
        )

        assertEquals(LimitRuleChangeStatus.REJECTED, result.status)
        assertEquals("Requested amount exceeds branch policy", result.decisionReason)
        assertEquals(now, result.decidedAt)
        assertEquals(now, result.updatedAt)
        assertEquals(null, result.limitRuleId)
        assertEquals(null, repositories.limitRules.findById(tenantId, pendingRuleId))
        assertEquals(emptyList(), repositories.limitRuleRevisions.findByChangeRequest(tenantId, request.id))

        val events = repositories.limitRuleChangeEvents.findByChangeRequest(tenantId, request.id)
        assertEquals(
            listOf(LimitRuleChangeEventType.REQUESTED, LimitRuleChangeEventType.REJECTED),
            events.map { it.eventType },
        )
        val rejectionEvent = events.last()
        assertEquals(ChangeActor.BankWorkerActor(BankWorkerId.of(approverId)), rejectionEvent.actor)
        assertEquals(DomainLimitRuleChangeStatus.REQUESTED, rejectionEvent.beforeStatus)
        assertEquals(DomainLimitRuleChangeStatus.REJECTED, rejectionEvent.afterStatus)
        assertEquals(request.requestedRule, rejectionEvent.beforeRule)
        assertEquals(request.requestedRule, rejectionEvent.afterRule)
        assertEquals(mapOf("caseId" to "rejection-1"), rejectionEvent.eventPayload.value)
    }

    @Test
    fun `rejectLimitRuleChange validates the reason and current status`() {
        val repositories = repositoriesWithConfiguration()
        val request = insertRequestedCreate(repositories)
        val service = limitChangeService(repositories)
        val actor = LimitChangeActorCommand(LimitChangeActorType.SYSTEM)

        assertFailsWith<ValidationException> {
            service.rejectLimitRuleChange(
                RejectLimitRuleChangeCommand(tenantId.value, request.id.value, actor, " "),
            )
        }

        service.rejectLimitRuleChange(
            RejectLimitRuleChangeCommand(tenantId.value, request.id.value, actor, "Not approved"),
        )
        assertFailsWith<ConflictException> {
            service.rejectLimitRuleChange(
                RejectLimitRuleChangeCommand(tenantId.value, request.id.value, actor, "Still not approved"),
            )
        }
    }

    @Test
    fun `cancelLimitRuleChange cancels a scheduled change with audit details`() {
        val repositories = repositoriesWithConfiguration()
        val request = insertRequestedCreate(
            repositories,
            command().copy(effectiveAt = now.plusSeconds(3600)),
        )
        val service = limitChangeService(repositories)
        service.approveLimitRuleChange(
            ApproveLimitRuleChangeCommand(
                tenantId = tenantId.value,
                changeRequestId = request.id.value,
                approvedBy = LimitChangeActorCommand(LimitChangeActorType.SYSTEM),
            )
        )
        val scheduled = assertNotNull(repositories.limitChangeRequests.findById(tenantId, request.id))
        val canceller = LimitChangeActorCommand(
            actorType = LimitChangeActorType.BANK_WORKER,
            bankWorkerId = approverId,
        )

        val result = service.cancelLimitRuleChange(
            CancelLimitRuleChangeCommand(
                tenantId = tenantId.value,
                changeRequestId = request.id.value,
                cancelledBy = canceller,
                decisionReason = "Customer withdrew the request",
                metadata = mapOf("caseId" to "cancellation-1"),
            )
        )

        assertEquals(LimitRuleChangeStatus.CANCELLED, result.status)
        assertEquals("Customer withdrew the request", result.decisionReason)
        assertEquals(now, result.cancelledAt)
        assertEquals(now, result.updatedAt)
        assertEquals(null, repositories.limitRules.findById(tenantId, pendingRuleId))
        assertEquals(emptyList(), repositories.limitRuleRevisions.findByChangeRequest(tenantId, request.id))

        val events = repositories.limitRuleChangeEvents.findByChangeRequest(tenantId, request.id)
        assertEquals(
            listOf(
                LimitRuleChangeEventType.REQUESTED,
                LimitRuleChangeEventType.APPROVED,
                LimitRuleChangeEventType.SCHEDULED,
                LimitRuleChangeEventType.CANCELLED,
            ),
            events.map { it.eventType },
        )
        val cancellationEvent = events.last()
        assertEquals(ChangeActor.BankWorkerActor(BankWorkerId.of(approverId)), cancellationEvent.actor)
        assertEquals(DomainLimitRuleChangeStatus.PENDING_EFFECTIVE_DATE, cancellationEvent.beforeStatus)
        assertEquals(DomainLimitRuleChangeStatus.CANCELLED, cancellationEvent.afterStatus)
        assertEquals(scheduled.approvedRule, cancellationEvent.beforeRule)
        assertEquals(scheduled.approvedRule, cancellationEvent.afterRule)
        assertEquals(mapOf("caseId" to "cancellation-1"), cancellationEvent.eventPayload.value)
    }

    @Test
    fun `cancelLimitRuleChange validates the reason and current status`() {
        val repositories = repositoriesWithConfiguration()
        val service = limitChangeService(repositories)
        val actor = LimitChangeActorCommand(LimitChangeActorType.SYSTEM)

        assertFailsWith<ValidationException> {
            service.cancelLimitRuleChange(
                CancelLimitRuleChangeCommand(tenantId.value, ownerId, actor, " "),
            )
        }

        val applied = service.requestLimitRuleChange(command())
        assertFailsWith<ConflictException> {
            service.cancelLimitRuleChange(
                CancelLimitRuleChangeCommand(tenantId.value, applied.id, actor),
            )
        }
    }

    @Test
    fun `applyDueLimitChanges applies scheduled changes that are due`() {
        val repositories = repositoriesWithConfiguration()
        val service = limitChangeService(repositories)
        val dueAt = now.plusSeconds(3600)
        val scheduled = service.requestLimitRuleChange(
            command().copy(effectiveAt = dueAt)
        )

        val earlyResult = service.applyDueLimitChanges(
            ApplyDueLimitChangesCommand(tenantId.value, dueAt.minusSeconds(1))
        )
        assertEquals(0, earlyResult.appliedCount)
        assertEquals(0, earlyResult.failedCount)

        val result = service.applyDueLimitChanges(
            ApplyDueLimitChangesCommand(tenantId.value, dueAt)
        )

        assertEquals(tenantId.value, result.tenantId)
        assertEquals(dueAt, result.dueAt)
        assertEquals(1, result.appliedCount)
        assertEquals(0, result.failedCount)
        assertEquals(listOf(scheduled.id), result.appliedChangeRequestIds)
        assertEquals(emptyList(), result.failedChangeRequestIds)

        val appliedRequest = assertNotNull(
            repositories.limitChangeRequests.findById(tenantId, LimitChangeRequestId.of(scheduled.id))
        )
        assertEquals(DomainLimitRuleChangeStatus.APPLIED, appliedRequest.status)
        assertEquals(dueAt, appliedRequest.appliedAt)
        val rule = assertNotNull(
            repositories.limitRules.findById(tenantId, requireNotNull(appliedRequest.limitRuleId))
        )
        assertEquals(dueAt, rule.updatedAt)
        val revision = repositories.limitRuleRevisions.findByChangeRequest(tenantId, appliedRequest.id).single()
        assertEquals(ChangeActor.System, revision.actor)
        assertEquals(dueAt, revision.effectiveAt)
        assertEquals(
            listOf(
                LimitRuleChangeEventType.REQUESTED,
                LimitRuleChangeEventType.POLICY_EVALUATED,
                LimitRuleChangeEventType.APPROVED,
                LimitRuleChangeEventType.SCHEDULED,
                LimitRuleChangeEventType.APPLIED,
            ),
            repositories.limitRuleChangeEvents
                .findByChangeRequest(tenantId, appliedRequest.id)
                .map { it.eventType },
        )
    }

    @Test
    fun `applyDueLimitChanges marks a stale scheduled change as failed`() {
        val repositories = repositoriesWithConfiguration()
        val currentRule = insertRule(repositories)
        val service = limitChangeService(repositories)
        val dueAt = now.plusSeconds(3600)
        val scheduled = service.requestLimitRuleChange(
            commandForExistingRule(
                changeType = LimitRuleChangeType.UPDATE,
                currentRule = currentRule,
                requestedRule = draft(amount = "700.00"),
                effectiveAt = dueAt,
            )
        )
        repositories.limitRules.update(
            tenantId,
            currentRule.copy(
                version = currentRule.version.next(),
                updatedAt = now.plusSeconds(60),
            ),
        )

        val result = service.applyDueLimitChanges(
            ApplyDueLimitChangesCommand(tenantId.value, dueAt)
        )

        assertEquals(0, result.appliedCount)
        assertEquals(1, result.failedCount)
        assertEquals(emptyList(), result.appliedChangeRequestIds)
        assertEquals(listOf(scheduled.id), result.failedChangeRequestIds)
        val failedRequest = assertNotNull(
            repositories.limitChangeRequests.findById(tenantId, LimitChangeRequestId.of(scheduled.id))
        )
        assertEquals(DomainLimitRuleChangeStatus.FAILED, failedRequest.status)
        assertEquals("limit rule version conflict: expected 1, found 2", failedRequest.decisionReason)
        assertEquals(null, failedRequest.cancelledAt)
        assertEquals(emptyList(), repositories.limitRuleRevisions.findByChangeRequest(tenantId, failedRequest.id))
        val events = repositories.limitRuleChangeEvents.findByChangeRequest(tenantId, failedRequest.id)
        assertEquals(LimitRuleChangeEventType.FAILED, events.last().eventType)
        assertEquals(DomainLimitRuleChangeStatus.PENDING_EFFECTIVE_DATE, events.last().beforeStatus)
        assertEquals(DomainLimitRuleChangeStatus.FAILED, events.last().afterStatus)
        assertEquals(
            mapOf("reason" to "limit rule version conflict: expected 1, found 2"),
            events.last().eventPayload.value,
        )
    }

    @Test
    fun `applyDueLimitChanges revalidates a mandate before creating the rule`() {
        val repositories = repositoriesWithConfiguration()
        repositories.mandates.insert(tenantId, mandate())
        val service = limitChangeService(repositories)
        val dueAt = now.plusSeconds(3600)
        val scheduled = service.requestLimitRuleChange(
            command().copy(
                requestedRule = draft().copy(
                    mandateScope = LimitMandateScope.MANDATE,
                    mandateId = mandateId.value,
                ),
                targetMandateId = mandateId.value,
                effectiveAt = dueAt,
            )
        )
        repositories.mandates.update(tenantId, mandate(isActive = false))

        val result = service.applyDueLimitChanges(
            ApplyDueLimitChangesCommand(tenantId.value, dueAt)
        )

        assertEquals(0, result.appliedCount)
        assertEquals(1, result.failedCount)
        assertEquals(listOf(scheduled.id), result.failedChangeRequestIds)
        val failedRequest = assertNotNull(
            repositories.limitChangeRequests.findById(tenantId, LimitChangeRequestId.of(scheduled.id))
        )
        assertEquals(DomainLimitRuleChangeStatus.FAILED, failedRequest.status)
        assertEquals(
            emptyList(),
            repositories.limitRules.findActiveForOwner(tenantId, PartyProfileId.of(ownerId), dueAt),
        )
    }

    @Test
    fun `applyDueLimitChanges uses the current time and validates the batch limit`() {
        val service = limitChangeService(repositoriesWithConfiguration())

        val result = service.applyDueLimitChanges(ApplyDueLimitChangesCommand(tenantId.value))
        assertEquals(now, result.dueAt)
        assertEquals(0, result.appliedCount)
        assertEquals(0, result.failedCount)

        assertFailsWith<ValidationException> {
            service.applyDueLimitChanges(ApplyDueLimitChangesCommand(tenantId.value, limit = 0))
        }
    }

    private fun repositoriesWithConfiguration(
        authorizationDecision: LimitChangePolicyDecision? = LimitChangePolicyDecision.ALLOW,
    ): InMemoryLimitguardRepositories {
        val repositories = InMemoryLimitguardRepositories()
        repositories.limitConfigurations.insert(
            tenantId,
            LimitConfiguration(
                id = configurationId,
                tenantId = tenantId,
                parentConfigurationId = null,
                code = LimitConfigurationCode.of("ATM_PER_TX"),
                name = LimitConfigurationName.of("ATM per transaction"),
                description = null,
                limitKind = DomainLimitKind.PerTransaction,
                fundingScope = LimitConfigurationFundingScope.AllFundingSources,
                channelScope = DomainLimitChannelScope.AllChannels,
                operationScope = LimitOperationScope.AllOperations,
                maxAssignableAmount = Money.positive("1000.00", CurrencyCode.of("EUR")),
                defaultAmount = null,
                priority = LimitPriority.of(100),
                isActive = true,
                effectiveWindow = EffectiveWindow(now.minusSeconds(60), null),
                metadata = Metadata.EMPTY,
                createdAt = now,
            )
        )
        if (authorizationDecision != null) {
            repositories.limitChangePolicies.insert(
                tenantId,
                authorizationPolicy(authorizationDecision),
            )
        }
        return repositories
    }

    private fun authorizationPolicy(
        decision: LimitChangePolicyDecision,
        id: LimitChangePolicyId = authorizationPolicyId,
        code: String = "AUTHORIZE_LIMIT_CHANGE",
        priority: Int = 100,
    ): LimitChangePolicy =
        LimitChangePolicy(
            id = id,
            tenantId = tenantId,
            code = LimitChangePolicyCode.of(code),
            name = LimitChangePolicyName.of(code.replace('_', ' ').lowercase()),
            phase = LimitChangePolicyPhase.AUTHORIZATION,
            effectiveWindow = EffectiveWindow(now.minusSeconds(60), null),
            engine = LimitChangePolicyEngine.JSON_LOGIC,
            decision = decision,
            priority = LimitPriority.of(priority),
            denialCode = if (decision == LimitChangePolicyDecision.DENY) "LIMIT_CHANGE_DENIED" else null,
            denialMessage = if (decision == LimitChangePolicyDecision.DENY) {
                "Change denied by test policy"
            } else {
                null
            },
            createdAt = now,
            updatedAt = now,
        )

    private fun insertRequestedCreate(
        repositories: InMemoryLimitguardRepositories,
        requestCommand: RequestLimitRuleChangeCommand = command(),
    ): LimitRuleChangeRequest {
        val facts = ChangeReferenceResolver(
            limitConfigurationRepository = repositories.limitConfigurations,
            limitRuleRepository = repositories.limitRules,
            limitRuleRevisionRepository = repositories.limitRuleRevisions,
        ).resolve(requestCommand)
        val candidate = LimitRuleCandidateFactory().createCandidate(
            command = requestCommand,
            facts = facts,
            futureRuleId = pendingRuleId,
            now = now,
        )
        val request = LimitChangeRequestFactory().create(
            id = pendingRequestId,
            command = requestCommand,
            requestedRule = candidate,
            currentRule = null,
            now = now,
        )
        val persisted = repositories.limitChangeRequests.insert(tenantId, request)
        repositories.limitRuleChangeEvents.insert(
            tenantId,
            LimitChangeEventFactory(SequentialIdGenerator(startAt = 900L)).requested(persisted, now),
        )
        return persisted
    }

    private fun limitChangeService(
        repositories: InMemoryLimitguardRepositories,
        clock: FixedClockProvider = FixedClockProvider(now),
        policyEngines: Collection<PolicyEngine> = listOf(TestPolicyEngine),
    ): DefaultLimitChangeService {
        val idGenerator = SequentialIdGenerator(startAt = 100L)
        val changeEventFactory = LimitChangeEventFactory(idGenerator)
        val mandateValidator = LimitRuleMandateValidator(repositories.mandates)
        val applicationWorkflow = LimitChangeApplicationWorkflow(
            changeRequestRepository = repositories.limitChangeRequests,
            changeEventRepository = repositories.limitRuleChangeEvents,
            limitRuleRepository = repositories.limitRules,
            limitRuleRevisionRepository = repositories.limitRuleRevisions,
            changeEventFactory = changeEventFactory,
            idGenerator = idGenerator,
        )
        val policyRunner = LimitChangePolicyRunner(
            policyRepository = repositories.limitChangePolicies,
            evaluationRepository = repositories.limitChangePolicyEvaluations,
            changeEventRepository = repositories.limitRuleChangeEvents,
            engineRegistry = PolicyEngineRegistry(policyEngines),
            changeEventFactory = changeEventFactory,
            idGenerator = idGenerator,
        )
        return DefaultLimitChangeService(
            requestWorkflow = RequestLimitRuleChangeWorkflow(
                commandValidator = ChangeCommandValidator(),
                referenceResolver = ChangeReferenceResolver(
                    limitConfigurationRepository = repositories.limitConfigurations,
                    limitRuleRepository = repositories.limitRules,
                    limitRuleRevisionRepository = repositories.limitRuleRevisions,
                ),
                candidateFactory = LimitRuleCandidateFactory(),
                mandateValidator = mandateValidator,
                changeRequestFactory = LimitChangeRequestFactory(),
                changeEventFactory = changeEventFactory,
                policyRunner = policyRunner,
                applicationWorkflow = applicationWorkflow,
                changeRequestRepository = repositories.limitChangeRequests,
                changeEventRepository = repositories.limitRuleChangeEvents,
                limitRuleRepository = repositories.limitRules,
                idGenerator = idGenerator,
                clockProvider = clock,
                transactionRunner = DirectTransactionRunner(),
            ),
            approveWorkflow = ApproveLimitRuleChangeWorkflow(
                candidateFactory = LimitRuleCandidateFactory(),
                mandateValidator = mandateValidator,
                applicationWorkflow = applicationWorkflow,
                changeEventFactory = changeEventFactory,
                changeRequestRepository = repositories.limitChangeRequests,
                changeEventRepository = repositories.limitRuleChangeEvents,
                limitConfigurationRepository = repositories.limitConfigurations,
                limitRuleRepository = repositories.limitRules,
                clockProvider = clock,
                transactionRunner = DirectTransactionRunner(),
            ),
            rejectWorkflow = RejectLimitRuleChangeWorkflow(
                changeEventFactory = changeEventFactory,
                changeRequestRepository = repositories.limitChangeRequests,
                changeEventRepository = repositories.limitRuleChangeEvents,
                clockProvider = clock,
                transactionRunner = DirectTransactionRunner(),
            ),
            cancelWorkflow = CancelLimitRuleChangeWorkflow(
                changeEventFactory = changeEventFactory,
                changeRequestRepository = repositories.limitChangeRequests,
                changeEventRepository = repositories.limitRuleChangeEvents,
                clockProvider = clock,
                transactionRunner = DirectTransactionRunner(),
            ),
            applyDueWorkflow = ApplyDueLimitChangesWorkflow(
                applicationWorkflow = applicationWorkflow,
                mandateValidator = mandateValidator,
                changeEventFactory = changeEventFactory,
                changeRequestRepository = repositories.limitChangeRequests,
                changeEventRepository = repositories.limitRuleChangeEvents,
                limitRuleRepository = repositories.limitRules,
                clockProvider = clock,
                transactionRunner = DirectTransactionRunner(),
            ),
        )
    }

    private fun mandate(
        owner: PartyProfileId = PartyProfileId.of(ownerId),
        assignee: PartyProfileId = PartyProfileId.of(requesterId),
        isActive: Boolean = true,
        effectiveWindow: EffectiveWindow = EffectiveWindow(now.minusSeconds(60), null),
    ): Mandate =
        Mandate(
            id = mandateId,
            tenantId = tenantId,
            ownerPartyProfileId = owner,
            assigneePartyProfileId = assignee,
            authorityDelegationId = null,
            name = MandateName.of("Payment delegate"),
            description = null,
            fundingScope = FundingSourceScope.AllOwnerSources,
            isActive = isActive,
            version = Version.INITIAL,
            effectiveWindow = effectiveWindow,
            metadata = Metadata.EMPTY,
            createdAt = now,
            updatedAt = now,
        )

    private fun insertRule(
        repositories: InMemoryLimitguardRepositories,
        isActive: Boolean = true,
        endsAt: Instant? = null,
    ): LimitRule {
        val rule = LimitRule(
            id = existingRuleId,
            tenantId = tenantId,
            ownerPartyProfileId = PartyProfileId.of(ownerId),
            name = LimitRuleName.of("Daily ATM limit"),
            description = null,
            limitKind = DomainLimitKind.PerTransaction,
            amount = Money.positive("500.00", CurrencyCode.of("EUR")),
            mandateScope = MandateScope.AllOwnerMandates,
            configurationId = configurationId,
            channelScope = DomainLimitChannelScope.AllChannels,
            operationScope = LimitOperationScope.AllOperations,
            usageConsumptionMode = DomainLimitUsageConsumptionMode.CASCADING,
            priority = LimitPriority.of(100),
            isActive = isActive,
            version = Version.INITIAL,
            effectiveWindow = EffectiveWindow(now.minusSeconds(60), endsAt),
            metadata = Metadata.EMPTY,
            createdAt = now.minusSeconds(60),
            updatedAt = now.minusSeconds(60),
        )
        return repositories.limitRules.insert(tenantId, rule)
    }

    private fun commandForExistingRule(
        changeType: LimitRuleChangeType,
        currentRule: LimitRule,
        requestedRule: LimitRuleDraft? = null,
        idempotencyKey: String? = null,
        effectiveAt: Instant? = null,
    ): RequestLimitRuleChangeCommand =
        RequestLimitRuleChangeCommand(
            tenantId = tenantId.value,
            changeType = changeType,
            requestedBy = LimitChangeActorCommand(
                actorType = LimitChangeActorType.PARTY_PROFILE,
                partyProfileId = requesterId,
            ),
            targetOwnerPartyProfileId = ownerId,
            requestedRule = requestedRule ?: if (changeType == LimitRuleChangeType.UPDATE) draft() else null,
            limitRuleId = currentRule.id.value,
            expectedVersion = currentRule.version.value,
            requestChannelId = channelId,
            effectiveAt = effectiveAt,
            idempotencyKey = idempotencyKey,
        )

    private fun draft(
        name: String = "Daily ATM limit",
        amount: String = "500.00",
    ): LimitRuleDraft =
        LimitRuleDraft(
            name = name,
            limitKind = LimitKind.PER_TRANSACTION,
            amount = BigDecimal(amount),
            currency = "EUR",
            configurationId = configurationId.value,
            channelScope = LimitChannelScope.ALL_CHANNELS,
            usageConsumptionMode = LimitUsageConsumptionMode.CASCADING,
            priority = 100,
        )

    private fun command(
        idempotencyKey: String? = null,
        name: String = "Daily ATM limit",
        amount: String = "500.00",
    ): RequestLimitRuleChangeCommand =
        RequestLimitRuleChangeCommand(
            tenantId = tenantId.value,
            changeType = LimitRuleChangeType.CREATE,
            requestedBy = LimitChangeActorCommand(
                actorType = LimitChangeActorType.PARTY_PROFILE,
                partyProfileId = requesterId,
            ),
            targetOwnerPartyProfileId = ownerId,
            requestedRule = draft(name, amount),
            requestChannelId = channelId,
            idempotencyKey = idempotencyKey,
        )

    private object TestPolicyEngine : PolicyEngine {
        override val engine: LimitChangePolicyEngine = LimitChangePolicyEngine.JSON_LOGIC

        override fun evaluate(
            policy: LimitChangePolicy,
            context: PolicyEvaluationContext,
        ): PolicyEvaluationResult {
            require(policy.phase == context.phase)
            val matched = policy.conditionExpression.value["matched"] != false
            val outcome = when {
                !matched -> LimitChangePolicyEvaluationOutcome.NOT_MATCHED
                policy.decision == LimitChangePolicyDecision.DENY ->
                    LimitChangePolicyEvaluationOutcome.DENIED
                policy.decision == LimitChangePolicyDecision.NO_OP ->
                    LimitChangePolicyEvaluationOutcome.MATCHED
                else -> LimitChangePolicyEvaluationOutcome.APPLIED
            }
            return PolicyEvaluationResult(
                matched = matched,
                outcome = outcome,
                decision = policy.decision,
            )
        }
    }

}
