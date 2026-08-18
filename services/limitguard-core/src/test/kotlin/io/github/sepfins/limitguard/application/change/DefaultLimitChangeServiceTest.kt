package io.github.sepfins.limitguard.application.change

import io.github.sepfins.limitguard.api.LimitChangeActorType
import io.github.sepfins.limitguard.api.LimitChannelScope
import io.github.sepfins.limitguard.api.LimitKind
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
import io.github.sepfins.limitguard.api.errors.ValidationException
import io.github.sepfins.limitguard.domain.common.CurrencyCode
import io.github.sepfins.limitguard.domain.common.EffectiveWindow
import io.github.sepfins.limitguard.domain.common.LimitConfigurationId
import io.github.sepfins.limitguard.domain.common.LimitChangeRequestId
import io.github.sepfins.limitguard.domain.common.LimitRuleId
import io.github.sepfins.limitguard.domain.common.Metadata
import io.github.sepfins.limitguard.domain.common.Money
import io.github.sepfins.limitguard.domain.common.PartyProfileId
import io.github.sepfins.limitguard.domain.common.TenantId
import io.github.sepfins.limitguard.domain.common.Version
import io.github.sepfins.limitguard.domain.change.LimitRuleChangeEventType
import io.github.sepfins.limitguard.domain.change.LimitRuleRevisionEventType
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
import io.github.sepfins.limitguard.domain.mandate.MandateScope
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

    @Test
    fun `requestLimitRuleChange applies create request with complete audit history`() {
        val repositories = repositoriesWithConfiguration()
        val service = limitChangeService(repositories)

        val result = service.requestLimitRuleChange(command(idempotencyKey = "request-1"))

        assertEquals(tenantId.value, result.tenantId)
        assertEquals(LimitRuleChangeType.CREATE, result.changeType)
        assertEquals(LimitRuleChangeStatus.APPLIED, result.status)
        assertEquals(ownerId, result.targetOwnerPartyProfileId)
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
        assertEquals(3, repositories.limitRuleChangeEvents.findByChangeRequest(
            tenantId,
            LimitChangeRequestId.of(first.id),
        ).size)
        assertEquals(1, repositories.limitRules.findActiveForOwner(tenantId, PartyProfileId.of(ownerId), now).size)
        assertEquals(1, repositories.limitRuleRevisions.findByChangeRequest(
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
    fun `non-create workflows fail explicitly while deferred`() {
        val service = limitChangeService(repositoriesWithConfiguration())
        val actor = LimitChangeActorCommand(
            actorType = LimitChangeActorType.PARTY_PROFILE,
            partyProfileId = requesterId,
        )

        assertFailsWith<UnsupportedOperationException> {
            service.approveLimitRuleChange(
                ApproveLimitRuleChangeCommand(tenantId.value, ownerId, actor),
            )
        }
        assertFailsWith<UnsupportedOperationException> {
            service.rejectLimitRuleChange(
                RejectLimitRuleChangeCommand(tenantId.value, ownerId, actor, "not approved"),
            )
        }
        assertFailsWith<UnsupportedOperationException> {
            service.cancelLimitRuleChange(
                CancelLimitRuleChangeCommand(tenantId.value, ownerId, actor),
            )
        }
        assertFailsWith<UnsupportedOperationException> {
            service.applyDueLimitChanges(ApplyDueLimitChangesCommand(tenantId.value))
        }
    }

    private fun repositoriesWithConfiguration(): InMemoryLimitguardRepositories {
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
        return repositories
    }

    private fun limitChangeService(
        repositories: InMemoryLimitguardRepositories,
        clock: FixedClockProvider = FixedClockProvider(now),
    ): DefaultLimitChangeService {
        val idGenerator = SequentialIdGenerator(startAt = 100L)
        return DefaultLimitChangeService(
            requestWorkflow = RequestLimitRuleChangeWorkflow(
                commandValidator = ChangeCommandValidator(),
                referenceResolver = ChangeReferenceResolver(
                    limitConfigurationRepository = repositories.limitConfigurations,
                    limitRuleRepository = repositories.limitRules,
                    limitRuleRevisionRepository = repositories.limitRuleRevisions,
                ),
                candidateFactory = LimitRuleCandidateFactory(),
                changeRequestFactory = LimitChangeRequestFactory(),
                changeEventFactory = LimitChangeEventFactory(idGenerator),
                changeRequestRepository = repositories.limitChangeRequests,
                changeEventRepository = repositories.limitRuleChangeEvents,
                limitRuleRepository = repositories.limitRules,
                limitRuleRevisionRepository = repositories.limitRuleRevisions,
                idGenerator = idGenerator,
                clockProvider = clock,
                transactionRunner = DirectTransactionRunner(),
            ),
        )
    }

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
}
