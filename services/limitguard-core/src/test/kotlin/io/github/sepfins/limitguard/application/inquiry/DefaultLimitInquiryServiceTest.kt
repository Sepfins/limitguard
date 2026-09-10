package io.github.sepfins.limitguard.application.inquiry

import io.github.sepfins.limitguard.api.LimitRuleChangeEventType
import io.github.sepfins.limitguard.api.LimitRuleChangeStatus
import io.github.sepfins.limitguard.api.LimitRuleRevisionEventType
import io.github.sepfins.limitguard.api.OperationCapacityStatus
import io.github.sepfins.limitguard.api.TransactionEventType
import io.github.sepfins.limitguard.api.queries.FindChannelByCodeQuery
import io.github.sepfins.limitguard.api.queries.FindFundingSourceByExternalReferenceQuery
import io.github.sepfins.limitguard.api.queries.FindFundingSourceProductByCodeQuery
import io.github.sepfins.limitguard.api.queries.FindLimitConfigurationByCodeQuery
import io.github.sepfins.limitguard.api.queries.FindOperationTypesByCodeQuery
import io.github.sepfins.limitguard.api.queries.FindTenantByNameQuery
import io.github.sepfins.limitguard.api.queries.GetCurrentUsageQuery
import io.github.sepfins.limitguard.api.queries.GetLimitChangeQuery
import io.github.sepfins.limitguard.api.queries.GetOperationCapacityQuery
import io.github.sepfins.limitguard.api.queries.GetTransactionHistoryQuery
import io.github.sepfins.limitguard.domain.change.ChangeActor
import io.github.sepfins.limitguard.domain.change.LimitRuleChangeEvent
import io.github.sepfins.limitguard.domain.change.LimitRuleChangeEventType as DomainLimitRuleChangeEventType
import io.github.sepfins.limitguard.domain.change.LimitRuleChangeRequest
import io.github.sepfins.limitguard.domain.change.LimitRuleChangeStatus as DomainLimitRuleChangeStatus
import io.github.sepfins.limitguard.domain.change.LimitRuleChangeType
import io.github.sepfins.limitguard.domain.change.LimitRuleRevision
import io.github.sepfins.limitguard.domain.change.LimitRuleRevisionEventType as DomainLimitRuleRevisionEventType
import io.github.sepfins.limitguard.domain.change.RequestActorContext
import io.github.sepfins.limitguard.domain.change.RevisionNumber
import io.github.sepfins.limitguard.domain.change.ScopeHash
import io.github.sepfins.limitguard.domain.channel.Channel
import io.github.sepfins.limitguard.domain.channel.ChannelCode
import io.github.sepfins.limitguard.domain.channel.ChannelName
import io.github.sepfins.limitguard.domain.common.AuthorizationDecisionId
import io.github.sepfins.limitguard.domain.common.ChannelId
import io.github.sepfins.limitguard.domain.common.CurrencyCode
import io.github.sepfins.limitguard.domain.common.EffectiveWindow
import io.github.sepfins.limitguard.domain.common.ExternalReference
import io.github.sepfins.limitguard.domain.common.FundingSourceId
import io.github.sepfins.limitguard.domain.common.FundingSourceOwnerId
import io.github.sepfins.limitguard.domain.common.FundingSourceProductId
import io.github.sepfins.limitguard.domain.common.LimitChangeRequestId
import io.github.sepfins.limitguard.domain.common.LimitConfigurationId
import io.github.sepfins.limitguard.domain.common.LimitRuleChangeEventId
import io.github.sepfins.limitguard.domain.common.LimitRuleId
import io.github.sepfins.limitguard.domain.common.LimitRuleRevisionId
import io.github.sepfins.limitguard.domain.common.Metadata
import io.github.sepfins.limitguard.domain.common.Money
import io.github.sepfins.limitguard.domain.common.OperationTypeId
import io.github.sepfins.limitguard.domain.common.PartyId
import io.github.sepfins.limitguard.domain.common.PartyProfileId
import io.github.sepfins.limitguard.domain.common.PaymentTransactionId
import io.github.sepfins.limitguard.domain.common.TenantId
import io.github.sepfins.limitguard.domain.common.TransactionEventId
import io.github.sepfins.limitguard.domain.common.UsageBucketId
import io.github.sepfins.limitguard.domain.common.UsageWindow
import io.github.sepfins.limitguard.domain.common.Version
import io.github.sepfins.limitguard.domain.funding.FundingSource
import io.github.sepfins.limitguard.domain.funding.FundingSourceCategory
import io.github.sepfins.limitguard.domain.funding.FundingSourceOwner
import io.github.sepfins.limitguard.domain.funding.FundingSourceProduct
import io.github.sepfins.limitguard.domain.funding.FundingSourceProductCode
import io.github.sepfins.limitguard.domain.funding.FundingSourceProductName
import io.github.sepfins.limitguard.domain.funding.ProductNodeType
import io.github.sepfins.limitguard.domain.limit.LimitChannelScope
import io.github.sepfins.limitguard.domain.limit.LimitConfiguration
import io.github.sepfins.limitguard.domain.limit.LimitConfigurationCode
import io.github.sepfins.limitguard.domain.limit.LimitConfigurationFundingScope
import io.github.sepfins.limitguard.domain.limit.LimitConfigurationName
import io.github.sepfins.limitguard.domain.limit.LimitKind
import io.github.sepfins.limitguard.domain.limit.LimitOperationScope
import io.github.sepfins.limitguard.domain.limit.LimitPeriod
import io.github.sepfins.limitguard.domain.limit.LimitPriority
import io.github.sepfins.limitguard.domain.limit.LimitRule
import io.github.sepfins.limitguard.domain.limit.LimitRuleName
import io.github.sepfins.limitguard.domain.limit.LimitUsageConsumptionMode
import io.github.sepfins.limitguard.domain.mandate.MandateScope
import io.github.sepfins.limitguard.domain.operation.OperationType
import io.github.sepfins.limitguard.domain.operation.OperationTypeCode
import io.github.sepfins.limitguard.domain.operation.OperationTypeName
import io.github.sepfins.limitguard.domain.operation.OperationTypeScope
import io.github.sepfins.limitguard.domain.party.PartyProfile
import io.github.sepfins.limitguard.domain.tenant.Tenant
import io.github.sepfins.limitguard.domain.tenant.TenantName
import io.github.sepfins.limitguard.domain.transaction.AuthorizationDecisionOutcome
import io.github.sepfins.limitguard.domain.transaction.AuthorizationDecisionRecord
import io.github.sepfins.limitguard.domain.transaction.PaymentTransaction
import io.github.sepfins.limitguard.domain.transaction.TransactionEvent
import io.github.sepfins.limitguard.domain.transaction.TransactionEventType as DomainTransactionEventType
import io.github.sepfins.limitguard.domain.transaction.TransactionResultSnapshot
import io.github.sepfins.limitguard.domain.transaction.TransactionStatus
import io.github.sepfins.limitguard.domain.usage.LimitUsageBucket
import io.github.sepfins.limitguard.support.DirectTransactionRunner
import io.github.sepfins.limitguard.support.FixedClockProvider
import io.github.sepfins.limitguard.support.persistence.InMemoryLimitguardRepositories
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class DefaultLimitInquiryServiceTest {
    private val now = Instant.parse("2026-08-09T10:15:00Z")
    private val tenantId = TenantId.of(id(1))

    @Test
    fun `returns change status workflow events and applied revision`() {
        val repositories = repositoriesWithTenant()
        val requestId = LimitChangeRequestId.of(id(10))
        val ruleId = LimitRuleId.of(id(11))
        repositories.limitChangeRequests.insert(
            tenantId,
            LimitRuleChangeRequest(
                id = requestId,
                tenantId = tenantId,
                changeType = LimitRuleChangeType.CREATE,
                requestedBy = ChangeActor.System,
                targetOwnerPartyProfileId = PartyProfileId.of(id(12)),
                targetScope = Metadata(mapOf("owner" to id(12).toString())),
                targetScopeHash = ScopeHash.of("scope-12"),
                requestedRule = Metadata(mapOf("amount" to "500.0000")),
                status = DomainLimitRuleChangeStatus.APPLIED,
                limitRuleId = ruleId,
                requestActorContext = RequestActorContext.System,
                approvedRule = Metadata(mapOf("amount" to "500.0000")),
                appliedAt = now.plusSeconds(2),
                createdAt = now,
                updatedAt = now.plusSeconds(2),
            ),
        )
        repositories.limitRuleChangeEvents.insert(
            tenantId,
            changeEvent(requestId, 20, DomainLimitRuleChangeEventType.REQUESTED, now),
        )
        repositories.limitRuleChangeEvents.insert(
            tenantId,
            changeEvent(requestId, 21, DomainLimitRuleChangeEventType.APPLIED, now.plusSeconds(2)),
        )
        repositories.limitRuleRevisions.insert(
            tenantId,
            LimitRuleRevision(
                id = LimitRuleRevisionId.of(id(22)),
                tenantId = tenantId,
                limitRuleId = ruleId,
                revisionNumber = RevisionNumber.of(1),
                revisionEventType = DomainLimitRuleRevisionEventType.CREATED,
                changeRequestId = requestId,
                afterRule = Metadata(mapOf("amount" to "500.0000")),
                createdAt = now.plusSeconds(2),
            ),
        )

        val result = inquiryService(repositories).getLimitChange(
            GetLimitChangeQuery(tenantId.value, requestId.value),
        )

        assertEquals(LimitRuleChangeStatus.APPLIED, result.request.status)
        assertEquals(
            listOf(LimitRuleChangeEventType.REQUESTED, LimitRuleChangeEventType.APPLIED),
            result.events.map { it.eventType },
        )
        assertEquals(LimitRuleRevisionEventType.CREATED, result.revisions.single().eventType)
        assertEquals("500.0000", result.approvedRule?.get("amount"))
    }

    @Test
    fun `returns transaction history and current per-assignee usage`() {
        val repositories = repositoriesWithTenant()
        val profileId = PartyProfileId.of(id(30))
        repositories.partyProfiles.insert(
            tenantId,
            PartyProfile(
                id = profileId,
                partyId = PartyId.of(id(31)),
                tenantId = tenantId,
                createdAt = now,
            ),
        )
        val transactionId = PaymentTransactionId.of(id(32))
        val authorizationEventId = TransactionEventId.of(id(33))
        repositories.transactions.insert(
            tenantId,
            PaymentTransaction(
                id = transactionId,
                tenantId = tenantId,
                actingPartyProfileId = profileId,
                fundingSourceId = FundingSourceId.of(id(34)),
                operationTypeId = OperationTypeId.of(id(35)),
                channelId = ChannelId.of(id(36)),
                authorizedAmount = eur("100"),
                capturedAmount = eur("40"),
                status = TransactionStatus.PARTIALLY_CAPTURED,
                metadata = Metadata(mapOf("merchant" to "demo")),
                createdAt = now,
            ),
        )
        repositories.transactionEvents.insert(
            tenantId,
            TransactionEvent(
                id = authorizationEventId,
                tenantId = tenantId,
                paymentTransactionId = transactionId,
                eventType = DomainTransactionEventType.AUTHORIZATION,
                amount = eur("100"),
                createdAt = now,
            ),
        )
        repositories.transactionEvents.insert(
            tenantId,
            TransactionEvent(
                id = TransactionEventId.of(id(37)),
                tenantId = tenantId,
                paymentTransactionId = transactionId,
                eventType = DomainTransactionEventType.PARTIAL_CAPTURE,
                amount = eur("40"),
                createdAt = now.plusSeconds(1),
            ),
        )
        repositories.authorizationDecisions.insert(
            tenantId,
            AuthorizationDecisionRecord(
                id = AuthorizationDecisionId.of(id(38)),
                tenantId = tenantId,
                actingPartyProfileId = profileId,
                fundingSourceId = FundingSourceId.of(id(34)),
                operationTypeId = OperationTypeId.of(id(35)),
                channelId = ChannelId.of(id(36)),
                requestedAmount = eur("100"),
                decision = AuthorizationDecisionOutcome.ALLOWED,
                paymentTransactionId = transactionId,
                transactionEventId = authorizationEventId,
                idempotencyKey = null,
                resultSnapshot = TransactionResultSnapshot(mapOf("snapshotVersion" to 1)),
                expiresAt = null,
                createdAt = now,
            ),
        )

        val ruleId = LimitRuleId.of(id(40))
        repositories.limitRules.insert(tenantId, periodicRule(ruleId, profileId))
        repositories.usageBuckets.insert(
            tenantId,
            LimitUsageBucket(
                id = UsageBucketId.of(id(41)),
                tenantId = tenantId,
                limitRuleId = ruleId,
                assigneePartyProfileId = profileId,
                window = UsageWindow(
                    Instant.parse("2026-08-09T00:00:00Z"),
                    Instant.parse("2026-08-10T00:00:00Z"),
                ),
                reservedAmount = eur("50"),
                consumedAmount = eur("125"),
                createdAt = now,
                updatedAt = now.plusSeconds(3),
            ),
        )
        val service = inquiryService(repositories)

        val history = service.getTransactionHistory(
            GetTransactionHistoryQuery(tenantId.value, transactionId.value),
        )
        val usage = service.getCurrentUsage(
            GetCurrentUsageQuery(tenantId.value, ruleId.value, profileId.value),
        )

        assertEquals("60.0000".toBigDecimal(), history.transaction.uncapturedAmount)
        assertEquals(
            listOf(TransactionEventType.AUTHORIZATION, TransactionEventType.PARTIAL_CAPTURE),
            history.events.map { it.eventType },
        )
        assertNotNull(history.authorizationDecision)
        assertEquals("50.0000".toBigDecimal(), usage.reservedAmount)
        assertEquals("125.0000".toBigDecimal(), usage.consumedAmount)
        assertEquals("325.0000".toBigDecimal(), usage.remainingAmount)
    }

    @Test
    fun `resolves operation capacity without requiring a rule id`() {
        val repositories = repositoriesWithTenant()
        val profileId = PartyProfileId.of(id(60))
        val productId = FundingSourceProductId.of(id(61))
        val fundingSourceId = FundingSourceId.of(id(62))
        val channelId = ChannelId.of(id(63))
        val operationTypeId = OperationTypeId.of(id(64))
        repositories.partyProfiles.insert(
            tenantId,
            PartyProfile(
                id = profileId,
                partyId = PartyId.of(id(65)),
                tenantId = tenantId,
                createdAt = now,
            ),
        )
        repositories.fundingSourceProducts.insert(
            tenantId,
            FundingSourceProduct(
                id = productId,
                tenantId = tenantId,
                parentId = null,
                accountCategory = FundingSourceCategory.TRANSACTIONAL,
                nodeType = ProductNodeType.PRODUCT,
                code = FundingSourceProductCode.of("DEBIT_CARD"),
                name = FundingSourceProductName.of("Debit card"),
                isLeaf = true,
                isActive = true,
                createdAt = now,
            ),
        )
        repositories.fundingSources.insert(
            tenantId,
            FundingSource(
                id = fundingSourceId,
                tenantId = tenantId,
                productId = productId,
                externalReference = ExternalReference.of("CARD-001"),
                isActive = true,
                createdAt = now,
            ),
        )
        repositories.fundingSourceOwners.insert(
            tenantId,
            FundingSourceOwner(
                id = FundingSourceOwnerId.of(id(66)),
                tenantId = tenantId,
                partyProfileId = profileId,
                fundingSourceId = fundingSourceId,
                createdAt = now,
            ),
        )
        repositories.channels.insert(
            tenantId,
            Channel(
                id = channelId,
                tenantId = tenantId,
                code = ChannelCode.of("MOBILE"),
                name = ChannelName.of("Mobile"),
                isActive = true,
                createdAt = now,
            ),
        )
        repositories.operationTypes.insert(
            tenantId,
            OperationType(
                id = operationTypeId,
                scope = OperationTypeScope.TenantSpecific(tenantId),
                parentId = null,
                code = OperationTypeCode.of("INSTANT_PAYMENT"),
                name = OperationTypeName.of("Instant payment"),
                isLeaf = true,
                createdAt = now,
            ),
        )

        val perTransactionRule = periodicRule(LimitRuleId.of(id(67)), profileId).copy(
            name = LimitRuleName.of("Per payment"),
            limitKind = LimitKind.PerTransaction,
            amount = eur("200"),
            channelScope = LimitChannelScope.ChannelSpecific(channelId),
            priority = LimitPriority.of(5),
        )
        val dailyRule = periodicRule(LimitRuleId.of(id(68)), profileId).copy(
            name = LimitRuleName.of("Daily payments"),
            operationScope = LimitOperationScope.OperationTypeSpecific(operationTypeId),
            priority = LimitPriority.of(10),
        )
        val monthlyRule = periodicRule(LimitRuleId.of(id(69)), profileId).copy(
            name = LimitRuleName.of("Monthly payments"),
            limitKind = LimitKind.Periodic(LimitPeriod.MONTHLY),
            amount = eur("1000"),
            priority = LimitPriority.of(20),
        )
        listOf(perTransactionRule, dailyRule, monthlyRule).forEach {
            repositories.limitRules.insert(tenantId, it)
        }
        repositories.usageBuckets.insert(
            tenantId,
            LimitUsageBucket(
                id = UsageBucketId.of(id(70)),
                tenantId = tenantId,
                limitRuleId = dailyRule.id,
                assigneePartyProfileId = profileId,
                window = UsageWindow(
                    Instant.parse("2026-08-09T00:00:00Z"),
                    Instant.parse("2026-08-10T00:00:00Z"),
                ),
                reservedAmount = eur("50"),
                consumedAmount = eur("125"),
                createdAt = now,
                updatedAt = now,
            ),
        )
        repositories.usageBuckets.insert(
            tenantId,
            LimitUsageBucket(
                id = UsageBucketId.of(id(71)),
                tenantId = tenantId,
                limitRuleId = monthlyRule.id,
                assigneePartyProfileId = profileId,
                window = UsageWindow(
                    Instant.parse("2026-08-01T00:00:00Z"),
                    Instant.parse("2026-09-01T00:00:00Z"),
                ),
                reservedAmount = eur("50"),
                consumedAmount = eur("800"),
                createdAt = now,
                updatedAt = now,
            ),
        )

        val result = inquiryService(repositories).getOperationCapacity(
            GetOperationCapacityQuery(
                tenantId = tenantId.value,
                actingPartyProfileId = profileId.value,
                fundingSourceId = fundingSourceId.value,
                operationTypeId = operationTypeId.value,
                channelId = channelId.value,
                currency = "EUR",
            ),
        )

        assertEquals(OperationCapacityStatus.AVAILABLE, result.status)
        assertEquals("150.0000".toBigDecimal(), result.effectiveAvailableAmount)
        assertEquals(listOf(monthlyRule.id.value), result.limitingRuleIds)
        assertEquals(
            listOf(perTransactionRule.id.value, dailyRule.id.value, monthlyRule.id.value),
            result.applicableLimits.map { it.limitRuleId },
        )
        assertNull(result.applicableLimits.first().consumedAmount)
        assertEquals("125.0000".toBigDecimal(), result.applicableLimits[1].consumedAmount)
        assertEquals("50.0000".toBigDecimal(), result.applicableLimits[1].reservedAmount)
        assertEquals("325.0000".toBigDecimal(), result.applicableLimits[1].availableAmount)
        assertEquals("150.0000".toBigDecimal(), result.applicableLimits[2].availableAmount)
    }

    @Test
    fun `catalog lookups use stable business keys and include inactive entries`() {
        val repositories = repositoriesWithTenant()
        val channel = Channel(
            id = ChannelId.of(id(50)),
            tenantId = tenantId,
            code = ChannelCode.of("ATM"),
            name = ChannelName.of("ATM"),
            isActive = false,
            createdAt = now,
        )
        repositories.channels.insert(tenantId, channel)
        val operationType = OperationType(
            id = OperationTypeId.of(id(51)),
            scope = OperationTypeScope.TenantSpecific(tenantId),
            parentId = null,
            code = OperationTypeCode.of("WITHDRAWAL"),
            name = OperationTypeName.of("Withdrawal"),
            isLeaf = true,
            createdAt = now,
        )
        repositories.operationTypes.insert(tenantId, operationType)
        val product = FundingSourceProduct(
            id = FundingSourceProductId.of(id(52)),
            tenantId = tenantId,
            parentId = null,
            accountCategory = FundingSourceCategory.TRANSACTIONAL,
            nodeType = ProductNodeType.PRODUCT,
            code = FundingSourceProductCode.of("CURRENT_ACCOUNT"),
            name = FundingSourceProductName.of("Current account"),
            isLeaf = true,
            isActive = false,
            createdAt = now,
        )
        repositories.fundingSourceProducts.insert(tenantId, product)
        repositories.fundingSources.insert(
            tenantId,
            FundingSource(
                id = FundingSourceId.of(id(53)),
                tenantId = tenantId,
                productId = product.id,
                externalReference = ExternalReference.of("ACC-001"),
                isActive = false,
                createdAt = now,
            ),
        )
        val configuration = LimitConfiguration(
            id = LimitConfigurationId.of(id(54)),
            tenantId = tenantId,
            parentConfigurationId = null,
            code = LimitConfigurationCode.of("ATM_DAILY"),
            name = LimitConfigurationName.of("ATM daily"),
            description = null,
            limitKind = LimitKind.Periodic(LimitPeriod.DAILY),
            fundingScope = LimitConfigurationFundingScope.AllFundingSources,
            channelScope = LimitChannelScope.AllChannels,
            operationScope = LimitOperationScope.AllOperations,
            maxAssignableAmount = eur("1000"),
            defaultAmount = eur("500"),
            priority = LimitPriority.of(100),
            isActive = false,
            effectiveWindow = EffectiveWindow(now.minus(1, ChronoUnit.DAYS), null),
            createdAt = now,
            updatedAt = now,
        )
        repositories.limitConfigurations.insert(tenantId, configuration)
        val service = inquiryService(repositories)

        assertEquals(
            tenantId.value,
            service.findTenantByName(FindTenantByNameQuery("Inquiry Bank"))?.id,
        )
        assertFalse(requireNotNull(service.findChannelByCode(FindChannelByCodeQuery(tenantId.value, "ATM"))).isActive)
        assertEquals(
            operationType.id.value,
            service.findOperationTypesByCode(
                FindOperationTypesByCodeQuery(tenantId.value, "WITHDRAWAL"),
            ).single().id,
        )
        assertFalse(
            requireNotNull(
                service.findFundingSourceProductByCode(
                    FindFundingSourceProductByCodeQuery(tenantId.value, "CURRENT_ACCOUNT"),
                )
            ).isActive
        )
        assertFalse(
            requireNotNull(
                service.findFundingSourceByExternalReference(
                    FindFundingSourceByExternalReferenceQuery(tenantId.value, "ACC-001"),
                )
            ).isActive
        )
        assertFalse(
            requireNotNull(
                service.findLimitConfigurationByCode(
                    FindLimitConfigurationByCodeQuery(tenantId.value, "ATM_DAILY"),
                )
            ).isActive
        )
        assertNull(service.findChannelByCode(FindChannelByCodeQuery(tenantId.value, "MOBILE")))
    }

    private fun repositoriesWithTenant(): InMemoryLimitguardRepositories =
        InMemoryLimitguardRepositories().also { repositories ->
            repositories.tenants.insert(
                Tenant(
                    id = tenantId,
                    name = TenantName.of("Inquiry Bank"),
                    config = Metadata.EMPTY,
                    createdAt = now,
                ),
            )
        }

    private fun inquiryService(repositories: InMemoryLimitguardRepositories) =
        DefaultLimitInquiryService(
            tenantRepository = repositories.tenants,
            channelRepository = repositories.channels,
            channelGroupMemberRepository = repositories.channelGroupMembers,
            operationTypeRepository = repositories.operationTypes,
            fundingSourceProductRepository = repositories.fundingSourceProducts,
            fundingSourceRepository = repositories.fundingSources,
            fundingSourceOwnerRepository = repositories.fundingSourceOwners,
            fundingSourceGroupMemberRepository = repositories.fundingSourceGroupMembers,
            mandateRepository = repositories.mandates,
            limitConfigurationRepository = repositories.limitConfigurations,
            partyProfileRepository = repositories.partyProfiles,
            limitChangeRequestRepository = repositories.limitChangeRequests,
            limitChangeEventRepository = repositories.limitRuleChangeEvents,
            limitRuleRevisionRepository = repositories.limitRuleRevisions,
            limitRuleRepository = repositories.limitRules,
            transactionRepository = repositories.transactions,
            transactionEventRepository = repositories.transactionEvents,
            authorizationDecisionRepository = repositories.authorizationDecisions,
            usageBucketRepository = repositories.usageBuckets,
            clockProvider = FixedClockProvider(now),
            transactionRunner = DirectTransactionRunner(),
        )

    private fun periodicRule(ruleId: LimitRuleId, ownerId: PartyProfileId): LimitRule =
        LimitRule(
            id = ruleId,
            tenantId = tenantId,
            ownerPartyProfileId = ownerId,
            name = LimitRuleName.of("Daily limit"),
            description = null,
            limitKind = LimitKind.Periodic(LimitPeriod.DAILY),
            amount = eur("500"),
            mandateScope = MandateScope.AllOwnerMandates,
            configurationId = null,
            channelScope = LimitChannelScope.AllChannels,
            operationScope = LimitOperationScope.AllOperations,
            usageConsumptionMode = LimitUsageConsumptionMode.CASCADING,
            priority = LimitPriority.of(100),
            isActive = true,
            version = Version.INITIAL,
            effectiveWindow = EffectiveWindow(now.minus(1, ChronoUnit.DAYS), null),
            createdAt = now,
            updatedAt = now,
        )

    private fun changeEvent(
        requestId: LimitChangeRequestId,
        sequence: Long,
        type: DomainLimitRuleChangeEventType,
        createdAt: Instant,
    ): LimitRuleChangeEvent =
        LimitRuleChangeEvent(
            id = LimitRuleChangeEventId.of(id(sequence)),
            tenantId = tenantId,
            changeRequestId = requestId,
            eventType = type,
            actor = ChangeActor.System,
            createdAt = createdAt,
        )

    private fun eur(amount: String): Money = Money.of(amount, CurrencyCode.of("EUR"))

    private fun id(value: Long): UUID = UUID(0L, value)
}
