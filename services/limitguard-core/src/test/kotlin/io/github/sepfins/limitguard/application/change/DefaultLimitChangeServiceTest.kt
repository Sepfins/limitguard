package io.github.sepfins.limitguard.application.change

import io.github.sepfins.limitguard.api.LimitChangeActorType
import io.github.sepfins.limitguard.api.LimitChannelScope
import io.github.sepfins.limitguard.api.LimitKind
import io.github.sepfins.limitguard.api.LimitRuleChangeStatus
import io.github.sepfins.limitguard.api.LimitRuleChangeType
import io.github.sepfins.limitguard.api.LimitUsageConsumptionMode
import io.github.sepfins.limitguard.api.commands.LimitChangeActorCommand
import io.github.sepfins.limitguard.api.commands.LimitRuleDraft
import io.github.sepfins.limitguard.api.commands.RequestLimitRuleChangeCommand
import io.github.sepfins.limitguard.api.errors.ConflictException
import io.github.sepfins.limitguard.api.errors.IdempotencyConflictException
import io.github.sepfins.limitguard.domain.common.CurrencyCode
import io.github.sepfins.limitguard.domain.common.EffectiveWindow
import io.github.sepfins.limitguard.domain.common.LimitConfigurationId
import io.github.sepfins.limitguard.domain.common.LimitChangeRequestId
import io.github.sepfins.limitguard.domain.common.Metadata
import io.github.sepfins.limitguard.domain.common.Money
import io.github.sepfins.limitguard.domain.common.TenantId
import io.github.sepfins.limitguard.domain.change.LimitRuleChangeEventType
import io.github.sepfins.limitguard.domain.limit.LimitChannelScope as DomainLimitChannelScope
import io.github.sepfins.limitguard.domain.limit.LimitConfiguration
import io.github.sepfins.limitguard.domain.limit.LimitConfigurationCode
import io.github.sepfins.limitguard.domain.limit.LimitConfigurationFundingScope
import io.github.sepfins.limitguard.domain.limit.LimitConfigurationName
import io.github.sepfins.limitguard.domain.limit.LimitKind as DomainLimitKind
import io.github.sepfins.limitguard.domain.limit.LimitOperationScope
import io.github.sepfins.limitguard.domain.limit.LimitPriority
import io.github.sepfins.limitguard.support.DirectTransactionRunner
import io.github.sepfins.limitguard.support.FixedClockProvider
import io.github.sepfins.limitguard.support.SequentialIdGenerator
import io.github.sepfins.limitguard.support.persistence.InMemoryLimitguardRepositories
import java.math.BigDecimal
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

    @Test
    fun `requestLimitRuleChange persists requested create request and audit event`() {
        val repositories = repositoriesWithConfiguration()
        val service = limitChangeService(repositories)

        val result = service.requestLimitRuleChange(command(idempotencyKey = "request-1"))

        assertEquals(tenantId.value, result.tenantId)
        assertEquals(LimitRuleChangeType.CREATE, result.changeType)
        assertEquals(LimitRuleChangeStatus.REQUESTED, result.status)
        assertEquals(ownerId, result.targetOwnerPartyProfileId)
        assertNotNull(result.targetScopeHash)
        assertEquals(now, result.createdAt)
        assertEquals(now, result.updatedAt)

        val insertedRequest = repositories.limitChangeRequests.findById(
            tenantId,
            LimitChangeRequestId.of(result.id),
        )
        assertNotNull(insertedRequest)
        assertEquals("500.0000", insertedRequest.requestedRule.value["amount"])
        assertEquals("PER_TRANSACTION", insertedRequest.targetScope.value["kind"])

        val events = repositories.limitRuleChangeEvents.findByChangeRequest(tenantId, insertedRequest.id)
        assertEquals(1, events.size)
        assertEquals(LimitRuleChangeEventType.REQUESTED, events.single().eventType)
    }

    @Test
    fun `same idempotency key returns existing request for the same command`() {
        val repositories = repositoriesWithConfiguration()
        val service = limitChangeService(repositories)

        val first = service.requestLimitRuleChange(command(idempotencyKey = "request-1"))
        val second = service.requestLimitRuleChange(command(idempotencyKey = "request-1"))

        assertEquals(first.id, second.id)
        assertEquals(1, repositories.limitRuleChangeEvents.findByChangeRequest(
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
    fun `second pending request for same target scope fails without idempotency key`() {
        val repositories = repositoriesWithConfiguration()
        val service = limitChangeService(repositories)

        service.requestLimitRuleChange(command(name = "Daily ATM limit"))

        assertFailsWith<ConflictException> {
            service.requestLimitRuleChange(command(name = "Another name, same target scope"))
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

    private fun limitChangeService(repositories: InMemoryLimitguardRepositories): DefaultLimitChangeService =
        DefaultLimitChangeService(
            changeRequestRepository = repositories.limitChangeRequests,
            changeEventRepository = repositories.limitRuleChangeEvents,
            limitConfigurationRepository = repositories.limitConfigurations,
            idGenerator = SequentialIdGenerator(startAt = 100L),
            clockProvider = FixedClockProvider(now),
            transactionRunner = DirectTransactionRunner(),
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
            requestedRule = LimitRuleDraft(
                name = name,
                limitKind = LimitKind.PER_TRANSACTION,
                amount = BigDecimal(amount),
                currency = "EUR",
                configurationId = configurationId.value,
                channelScope = LimitChannelScope.ALL_CHANNELS,
                usageConsumptionMode = LimitUsageConsumptionMode.CASCADING,
                priority = 100,
            ),
            requestChannelId = channelId,
            idempotencyKey = idempotencyKey,
        )
}
