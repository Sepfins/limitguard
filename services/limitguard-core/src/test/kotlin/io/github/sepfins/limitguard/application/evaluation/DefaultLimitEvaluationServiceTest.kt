package io.github.sepfins.limitguard.application.evaluation

import io.github.sepfins.limitguard.api.AuthorizationDecision
import io.github.sepfins.limitguard.api.commands.AuthorizeTransactionCommand
import io.github.sepfins.limitguard.api.errors.IdempotencyConflictException
import io.github.sepfins.limitguard.application.transaction.TransactionRecorder
import io.github.sepfins.limitguard.domain.channel.Channel
import io.github.sepfins.limitguard.domain.channel.ChannelCode
import io.github.sepfins.limitguard.domain.channel.ChannelGroupMember
import io.github.sepfins.limitguard.domain.channel.ChannelName
import io.github.sepfins.limitguard.domain.common.ChannelGroupId
import io.github.sepfins.limitguard.domain.common.ChannelId
import io.github.sepfins.limitguard.domain.common.CurrencyCode
import io.github.sepfins.limitguard.domain.common.EffectiveWindow
import io.github.sepfins.limitguard.domain.common.FundingSourceGroupId
import io.github.sepfins.limitguard.domain.common.FundingSourceId
import io.github.sepfins.limitguard.domain.common.FundingSourceOwnerId
import io.github.sepfins.limitguard.domain.common.FundingSourceProductId
import io.github.sepfins.limitguard.domain.common.LimitRuleId
import io.github.sepfins.limitguard.domain.common.MandateId
import io.github.sepfins.limitguard.domain.common.Metadata
import io.github.sepfins.limitguard.domain.common.Money
import io.github.sepfins.limitguard.domain.common.OperationTypeId
import io.github.sepfins.limitguard.domain.common.PartyId
import io.github.sepfins.limitguard.domain.common.PartyProfileId
import io.github.sepfins.limitguard.domain.common.PaymentTransactionId
import io.github.sepfins.limitguard.domain.common.TenantId
import io.github.sepfins.limitguard.domain.common.TransactionEventId
import io.github.sepfins.limitguard.domain.common.Version
import io.github.sepfins.limitguard.domain.funding.FundingSource
import io.github.sepfins.limitguard.domain.funding.FundingSourceGroupMember
import io.github.sepfins.limitguard.domain.funding.FundingSourceOwner
import io.github.sepfins.limitguard.domain.funding.FundingSourceOwnerRole
import io.github.sepfins.limitguard.domain.funding.FundingSourceScope
import io.github.sepfins.limitguard.domain.limit.LimitChannelScope
import io.github.sepfins.limitguard.domain.limit.LimitKind
import io.github.sepfins.limitguard.domain.limit.LimitOperationScope
import io.github.sepfins.limitguard.domain.limit.LimitPeriod
import io.github.sepfins.limitguard.domain.limit.LimitPriority
import io.github.sepfins.limitguard.domain.limit.LimitRule
import io.github.sepfins.limitguard.domain.limit.LimitRuleName
import io.github.sepfins.limitguard.domain.limit.LimitUsageConsumptionMode
import io.github.sepfins.limitguard.domain.mandate.Mandate
import io.github.sepfins.limitguard.domain.mandate.MandateName
import io.github.sepfins.limitguard.domain.mandate.MandateScope
import io.github.sepfins.limitguard.domain.operation.OperationType
import io.github.sepfins.limitguard.domain.operation.OperationTypeCode
import io.github.sepfins.limitguard.domain.operation.OperationTypeName
import io.github.sepfins.limitguard.domain.operation.OperationTypeScope
import io.github.sepfins.limitguard.domain.party.PartyProfile
import io.github.sepfins.limitguard.domain.tenant.Tenant
import io.github.sepfins.limitguard.domain.tenant.TenantName
import io.github.sepfins.limitguard.domain.transaction.TransactionEventType
import io.github.sepfins.limitguard.application.usage.UsageReservationService
import io.github.sepfins.limitguard.application.usage.UsageWindowCalculator
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
import kotlin.test.assertNull

class DefaultLimitEvaluationServiceTest {
    private val now = Instant.parse("2026-06-28T10:15:30Z")
    private val tenantId = TenantId.of(uuid(1))
    private val actorId = PartyProfileId.of(uuid(2))
    private val firstOwnerId = PartyProfileId.of(uuid(3))
    private val secondOwnerId = PartyProfileId.of(uuid(4))
    private val fundingSourceId = FundingSourceId.of(uuid(10))
    private val channelId = ChannelId.of(uuid(11))
    private val operationTypeId = OperationTypeId.of(uuid(12))

    @Test
    fun `under-limit delegated transaction is recorded and allowed`() {
        val fixture = fixture(firstOwnerId)
        val mandate = fixture.addMandate(
            id = 100,
            ownerId = firstOwnerId,
            scope = FundingSourceScope.FundingSourceSpecific(fundingSourceId),
        )
        val rule = fixture.addRule(
            id = 200,
            ownerId = firstOwnerId,
            mandateScope = MandateScope.MandateSpecific(mandate.id),
            amount = "100.00",
        )

        val result = fixture.service.authorizeTransaction(command(amount = "75.00"))

        assertEquals(AuthorizationDecision.ALLOWED, result.decision)
        assertNotNull(result.paymentTransactionId)
        assertNotNull(result.transactionEventId)
        assertEquals(listOf(rule.id.value), result.matchedRules.map { it.id })
        assertEquals(1, fixture.repositories.transactions.findByFundingSourceId(tenantId, fundingSourceId).size)
        assertEquals(
            TransactionEventType.AUTHORIZATION,
            fixture.repositories.transactionEvents
                .findByTransaction(
                    tenantId,
                    PaymentTransactionId.of(requireNotNull(result.paymentTransactionId)),
                )
                .single()
                .eventType,
        )
    }

    @Test
    fun `over-limit transaction is denied without persistence`() {
        val fixture = fixture(firstOwnerId)
        val mandate = fixture.addMandate(
            id = 100,
            ownerId = firstOwnerId,
            scope = FundingSourceScope.AllOwnerSources,
        )
        val rule = fixture.addRule(
            id = 200,
            ownerId = firstOwnerId,
            mandateScope = MandateScope.MandateSpecific(mandate.id),
            amount = "100.00",
        )

        val result = fixture.service.authorizeTransaction(command(amount = "100.01"))

        assertEquals(AuthorizationDecision.DENIED, result.decision)
        assertNull(result.paymentTransactionId)
        assertNull(result.transactionEventId)
        assertEquals("PER_TRANSACTION_LIMIT_EXCEEDED", result.violations.single().code)
        assertEquals(rule.id.value, result.violations.single().limitRuleId)
        assertEquals(emptyList(), fixture.repositories.transactions.findByFundingSourceId(tenantId, fundingSourceId))
    }

    @Test
    fun `successful authorization replay returns the original result and rejects changed intent`() {
        val fixture = fixture(firstOwnerId)
        fixture.addMandate(
            id = 100,
            ownerId = firstOwnerId,
            scope = FundingSourceScope.AllOwnerSources,
        )
        fixture.addRule(
            id = 200,
            ownerId = firstOwnerId,
            mandateScope = MandateScope.AllOwnerMandates,
            amount = "100.00",
        )

        val first = fixture.service.authorizeTransaction(command(amount = "75.00", idempotencyKey = "auth-1"))
        val replay = fixture.service.authorizeTransaction(command(amount = "75.00", idempotencyKey = "auth-1"))

        assertEquals(first, replay)
        assertEquals(1, fixture.repositories.transactions.findByFundingSourceId(tenantId, fundingSourceId).size)
        assertFailsWith<IdempotencyConflictException> {
            fixture.service.authorizeTransaction(command(amount = "76.00", idempotencyKey = "auth-1"))
        }
    }

    @Test
    fun `all group and source mandates may overlap and every attached rule is enforced`() {
        val fixture = fixture(firstOwnerId)
        val groupId = FundingSourceGroupId.of(uuid(50))
        fixture.repositories.fundingSourceGroupMembers.add(
            tenantId,
            FundingSourceGroupMember(
                tenantId = tenantId,
                groupId = groupId,
                ownerPartyProfileId = firstOwnerId,
                fundingSourceId = fundingSourceId,
                createdAt = now,
            ),
        )
        val allSourcesMandate = fixture.addMandate(
            id = 100,
            ownerId = firstOwnerId,
            scope = FundingSourceScope.AllOwnerSources,
        )
        val groupMandate = fixture.addMandate(
            id = 101,
            ownerId = firstOwnerId,
            scope = FundingSourceScope.FundingSourceGroupSpecific(groupId),
        )
        val sourceMandate = fixture.addMandate(
            id = 102,
            ownerId = firstOwnerId,
            scope = FundingSourceScope.FundingSourceSpecific(fundingSourceId),
        )
        val rules = listOf(
            fixture.addRule(200, firstOwnerId, MandateScope.AllOwnerMandates, "1000.00", priority = 40),
            fixture.addRule(201, firstOwnerId, MandateScope.MandateSpecific(allSourcesMandate.id), "500.00", priority = 30),
            fixture.addRule(202, firstOwnerId, MandateScope.MandateSpecific(groupMandate.id), "300.00", priority = 20),
            fixture.addRule(203, firstOwnerId, MandateScope.MandateSpecific(sourceMandate.id), "100.00", priority = 10),
        )

        val result = fixture.service.authorizeTransaction(command(amount = "150.00"))

        assertEquals(AuthorizationDecision.DENIED, result.decision)
        assertEquals(
            rules.sortedBy { it.priority.value }.map { it.id.value },
            result.matchedRules.map { it.id },
        )
        assertEquals(listOf(uuid(203)), result.violations.map { it.limitRuleId })
    }

    @Test
    fun `co-owners may grant separate mandates and rules from both authority paths apply`() {
        val fixture = fixture(firstOwnerId, secondOwnerId)
        val firstMandate = fixture.addMandate(
            id = 100,
            ownerId = firstOwnerId,
            scope = FundingSourceScope.FundingSourceSpecific(fundingSourceId),
        )
        val secondMandate = fixture.addMandate(
            id = 101,
            ownerId = secondOwnerId,
            scope = FundingSourceScope.AllOwnerSources,
        )
        val restrictiveRule = fixture.addRule(
            id = 200,
            ownerId = firstOwnerId,
            mandateScope = MandateScope.MandateSpecific(firstMandate.id),
            amount = "100.00",
        )
        val otherOwnerRule = fixture.addRule(
            id = 201,
            ownerId = secondOwnerId,
            mandateScope = MandateScope.MandateSpecific(secondMandate.id),
            amount = "200.00",
        )

        val result = fixture.service.authorizeTransaction(command(amount = "150.00"))

        assertEquals(AuthorizationDecision.DENIED, result.decision)
        assertEquals(
            setOf(restrictiveRule.id.value, otherOwnerRule.id.value),
            result.matchedRules.mapTo(mutableSetOf()) { it.id },
        )
        assertEquals(restrictiveRule.id.value, result.violations.single().limitRuleId)
    }

    @Test
    fun `group mandate does not authorize a source outside the group`() {
        val fixture = fixture(firstOwnerId)
        fixture.addMandate(
            id = 100,
            ownerId = firstOwnerId,
            scope = FundingSourceScope.FundingSourceGroupSpecific(FundingSourceGroupId.of(uuid(50))),
        )

        val result = fixture.service.authorizeTransaction(command(amount = "10.00"))

        assertEquals(AuthorizationDecision.DENIED, result.decision)
        assertEquals("ACTOR_NOT_AUTHORIZED", result.violations.single().code)
        assertEquals(emptyList(), fixture.repositories.transactions.findByFundingSourceId(tenantId, fundingSourceId))
    }

    @Test
    fun `inactive mandate does not authorize an otherwise matching source`() {
        val fixture = fixture(firstOwnerId)
        fixture.addMandate(
            id = 100,
            ownerId = firstOwnerId,
            scope = FundingSourceScope.AllOwnerSources,
            isActive = false,
        )

        val result = fixture.service.authorizeTransaction(command(amount = "10.00"))

        assertEquals(AuthorizationDecision.DENIED, result.decision)
        assertEquals("ACTOR_NOT_AUTHORIZED", result.violations.single().code)
    }

    @Test
    fun `channel-group rule matches through channel membership`() {
        val fixture = fixture(firstOwnerId)
        fixture.addMandate(
            id = 100,
            ownerId = firstOwnerId,
            scope = FundingSourceScope.AllOwnerSources,
        )
        val channelGroupId = ChannelGroupId.of(uuid(60))
        fixture.repositories.channelGroupMembers.add(
            tenantId,
            ChannelGroupMember(
                tenantId = tenantId,
                groupId = channelGroupId,
                channelId = channelId,
                createdAt = now,
            ),
        )
        val rule = fixture.addRule(
            id = 200,
            ownerId = firstOwnerId,
            mandateScope = MandateScope.AllOwnerMandates,
            amount = "25.00",
            channelScope = LimitChannelScope.ChannelGroupSpecific(channelGroupId),
        )

        val result = fixture.service.authorizeTransaction(command(amount = "30.00"))

        assertEquals(AuthorizationDecision.DENIED, result.decision)
        assertEquals(rule.id.value, result.violations.single().limitRuleId)
    }

    @Test
    fun `direct owner needs no self-mandate`() {
        val fixture = fixture(actorId)

        val result = fixture.service.authorizeTransaction(command(amount = "10.00"))

        assertEquals(AuthorizationDecision.ALLOWED, result.decision)
        assertEquals(emptyList(), result.matchedRules)
        assertNotNull(result.paymentTransactionId)
    }

    @Test
    fun `all-owner rule applies to direct owner without a self-mandate`() {
        val fixture = fixture(actorId)
        val rule = fixture.addRule(
            id = 200,
            ownerId = actorId,
            mandateScope = MandateScope.AllOwnerMandates,
            amount = "5.00",
        )

        val result = fixture.service.authorizeTransaction(command(amount = "10.00"))

        assertEquals(AuthorizationDecision.DENIED, result.decision)
        assertEquals(rule.id.value, result.violations.single().limitRuleId)
    }

    @Test
    fun `periodic reservations accumulate and crossing the cap leaves usage unchanged`() {
        val fixture = fixture(firstOwnerId)
        fixture.addMandate(
            id = 100,
            ownerId = firstOwnerId,
            scope = FundingSourceScope.AllOwnerSources,
        )
        val rule = fixture.addRule(
            id = 200,
            ownerId = firstOwnerId,
            mandateScope = MandateScope.AllOwnerMandates,
            amount = "100.00",
            limitKind = LimitKind.Periodic(LimitPeriod.DAILY),
        )

        assertEquals(
            AuthorizationDecision.ALLOWED,
            fixture.service.authorizeTransaction(command(amount = "60.00")).decision,
        )
        assertEquals(
            AuthorizationDecision.ALLOWED,
            fixture.service.authorizeTransaction(command(amount = "40.00")).decision,
        )

        val denied = fixture.service.authorizeTransaction(command(amount = "0.01"))

        assertEquals(AuthorizationDecision.DENIED, denied.decision)
        assertEquals("PERIODIC_LIMIT_EXCEEDED", denied.violations.single().code)
        assertEquals(rule.id.value, denied.violations.single().limitRuleId)
        val bucket = fixture.periodicBucket(rule, LimitPeriod.DAILY)
        assertEquals(Money.positive("100.00", CurrencyCode.of("EUR")), bucket.reservedAmount)
        assertEquals(2, fixture.repositories.usageEvents.findByBucket(tenantId, bucket.id).size)
        assertEquals(2, fixture.repositories.transactions.findByFundingSourceId(tenantId, fundingSourceId).size)
    }

    @Test
    fun `authorization replay does not reserve periodic usage twice`() {
        val fixture = fixture(firstOwnerId)
        fixture.addMandate(
            id = 100,
            ownerId = firstOwnerId,
            scope = FundingSourceScope.AllOwnerSources,
        )
        val rule = fixture.addRule(
            id = 200,
            ownerId = firstOwnerId,
            mandateScope = MandateScope.AllOwnerMandates,
            amount = "100.00",
            limitKind = LimitKind.Periodic(LimitPeriod.DAILY),
        )

        val first = fixture.service.authorizeTransaction(command(amount = "60.00", idempotencyKey = "periodic-1"))
        val replay = fixture.service.authorizeTransaction(command(amount = "60.00", idempotencyKey = "periodic-1"))

        assertEquals(first, replay)
        val bucket = fixture.periodicBucket(rule, LimitPeriod.DAILY)
        assertEquals(Money.positive("60.00", CurrencyCode.of("EUR")), bucket.reservedAmount)
        assertEquals(1, fixture.repositories.usageEvents.findByBucket(tenantId, bucket.id).size)
    }

    @Test
    fun `cascading periodic rules reserve every matched bucket`() {
        val fixture = fixture(firstOwnerId)
        fixture.addMandate(
            id = 100,
            ownerId = firstOwnerId,
            scope = FundingSourceScope.AllOwnerSources,
        )
        val dailyRule = fixture.addRule(
            id = 200,
            ownerId = firstOwnerId,
            mandateScope = MandateScope.AllOwnerMandates,
            amount = "100.00",
            priority = 10,
            limitKind = LimitKind.Periodic(LimitPeriod.DAILY),
        )
        val monthlyRule = fixture.addRule(
            id = 201,
            ownerId = firstOwnerId,
            mandateScope = MandateScope.AllOwnerMandates,
            amount = "500.00",
            priority = 20,
            limitKind = LimitKind.Periodic(LimitPeriod.MONTHLY),
        )

        val result = fixture.service.authorizeTransaction(command(amount = "25.00"))

        assertEquals(AuthorizationDecision.ALLOWED, result.decision)
        val events = fixture.repositories.usageEvents.findByTransactionEvent(
            tenantId,
            TransactionEventId.of(requireNotNull(result.transactionEventId)),
        )
        assertEquals(setOf(dailyRule.id, monthlyRule.id), events.mapTo(mutableSetOf()) { it.limitRuleId })
    }

    @Test
    fun `isolated periodic rule stops broader owner usage but not per-transaction checks`() {
        val fixture = fixture(firstOwnerId)
        fixture.addMandate(
            id = 100,
            ownerId = firstOwnerId,
            scope = FundingSourceScope.AllOwnerSources,
        )
        val isolatedRule = fixture.addRule(
            id = 200,
            ownerId = firstOwnerId,
            mandateScope = MandateScope.AllOwnerMandates,
            amount = "1000.00",
            priority = 10,
            limitKind = LimitKind.Periodic(LimitPeriod.DAILY),
            usageConsumptionMode = LimitUsageConsumptionMode.ISOLATED,
        )
        val peerRule = fixture.addRule(
            id = 203,
            ownerId = firstOwnerId,
            mandateScope = MandateScope.AllOwnerMandates,
            amount = "2000.00",
            priority = 10,
            limitKind = LimitKind.Periodic(LimitPeriod.MONTHLY),
        )
        val broaderRule = fixture.addRule(
            id = 201,
            ownerId = firstOwnerId,
            mandateScope = MandateScope.AllOwnerMandates,
            amount = "5000.00",
            priority = 20,
            limitKind = LimitKind.Periodic(LimitPeriod.MONTHLY),
        )
        val perTransactionRule = fixture.addRule(
            id = 202,
            ownerId = firstOwnerId,
            mandateScope = MandateScope.AllOwnerMandates,
            amount = "50.00",
            priority = 30,
        )

        val allowed = fixture.service.authorizeTransaction(command(amount = "40.00"))

        assertEquals(AuthorizationDecision.ALLOWED, allowed.decision)
        assertEquals(
            listOf(isolatedRule.id.value, peerRule.id.value, perTransactionRule.id.value),
            allowed.matchedRules.map { it.id },
        )
        val allowedEvents = fixture.repositories.usageEvents.findByTransactionEvent(
            tenantId,
            TransactionEventId.of(
                requireNotNull(allowed.transactionEventId),
            ),
        )
        assertEquals(setOf(isolatedRule.id, peerRule.id), allowedEvents.mapTo(mutableSetOf()) { it.limitRuleId })
        assertEquals(
            emptyList(),
            fixture.repositories.usageBuckets.findByLimitRuleInWindow(
                tenantId,
                broaderRule.id,
                UsageWindowCalculator().calculateWindow(now, LimitPeriod.MONTHLY),
            ),
        )

        val denied = fixture.service.authorizeTransaction(command(amount = "60.00"))

        assertEquals(AuthorizationDecision.DENIED, denied.decision)
        assertEquals("PER_TRANSACTION_LIMIT_EXCEEDED", denied.violations.single().code)
        assertEquals(perTransactionRule.id.value, denied.violations.single().limitRuleId)
        assertEquals(
            Money.positive("40.00", CurrencyCode.of("EUR")),
            fixture.periodicBucket(isolatedRule, LimitPeriod.DAILY).reservedAmount,
        )
    }

    @Test
    fun `isolated rule from one co-owner does not suppress another owners usage`() {
        val fixture = fixture(firstOwnerId, secondOwnerId)
        val firstMandate = fixture.addMandate(
            id = 100,
            ownerId = firstOwnerId,
            scope = FundingSourceScope.AllOwnerSources,
        )
        val secondMandate = fixture.addMandate(
            id = 101,
            ownerId = secondOwnerId,
            scope = FundingSourceScope.AllOwnerSources,
        )
        val isolatedRule = fixture.addRule(
            id = 200,
            ownerId = firstOwnerId,
            mandateScope = MandateScope.MandateSpecific(firstMandate.id),
            amount = "100.00",
            priority = 10,
            limitKind = LimitKind.Periodic(LimitPeriod.DAILY),
            usageConsumptionMode = LimitUsageConsumptionMode.ISOLATED,
        )
        val otherOwnerRule = fixture.addRule(
            id = 201,
            ownerId = secondOwnerId,
            mandateScope = MandateScope.MandateSpecific(secondMandate.id),
            amount = "500.00",
            priority = 20,
            limitKind = LimitKind.Periodic(LimitPeriod.MONTHLY),
        )

        val result = fixture.service.authorizeTransaction(command(amount = "25.00"))

        assertEquals(AuthorizationDecision.ALLOWED, result.decision)
        val events = fixture.repositories.usageEvents.findByTransactionEvent(
            tenantId,
            TransactionEventId.of(requireNotNull(result.transactionEventId)),
        )
        assertEquals(setOf(isolatedRule.id, otherOwnerRule.id), events.mapTo(mutableSetOf()) { it.limitRuleId })
    }

    @Test
    fun `different-currency rule fails closed instead of being skipped`() {
        val fixture = fixture(firstOwnerId)
        fixture.addMandate(
            id = 100,
            ownerId = firstOwnerId,
            scope = FundingSourceScope.AllOwnerSources,
        )
        val rule = fixture.addRule(
            id = 200,
            ownerId = firstOwnerId,
            mandateScope = MandateScope.AllOwnerMandates,
            amount = "100.00",
        )

        val result = fixture.service.authorizeTransaction(command(amount = "10.00", currency = "USD"))

        assertEquals(AuthorizationDecision.DENIED, result.decision)
        assertEquals("LIMIT_CURRENCY_MISMATCH", result.violations.single().code)
        assertEquals(rule.id.value, result.violations.single().limitRuleId)
        assertEquals(emptyList(), fixture.repositories.transactions.findByFundingSourceId(tenantId, fundingSourceId))
    }

    private fun fixture(
        ownerId: PartyProfileId,
        additionalOwnerId: PartyProfileId? = null,
    ): AuthorizationFixture =
        AuthorizationFixture(listOfNotNull(ownerId, additionalOwnerId))

    private fun command(
        amount: String,
        idempotencyKey: String? = null,
        currency: String = "EUR",
    ): AuthorizeTransactionCommand =
        AuthorizeTransactionCommand(
            tenantId = tenantId.value,
            actingPartyProfileId = actorId.value,
            fundingSourceId = fundingSourceId.value,
            operationTypeId = operationTypeId.value,
            channelId = channelId.value,
            amount = BigDecimal(amount),
            currency = currency,
            idempotencyKey = idempotencyKey,
            metadata = mapOf("merchantCategory" to "GROCERY"),
        )

    private inner class AuthorizationFixture(ownerIds: List<PartyProfileId>) {
        val repositories = InMemoryLimitguardRepositories()
        val service: DefaultLimitEvaluationService

        init {
            repositories.tenants.insert(
                Tenant(
                    id = tenantId,
                    name = TenantName.of("Test Bank"),
                    createdAt = now,
                ),
            )
            (ownerIds + actorId)
                .distinct()
                .forEach(::addPartyProfile)
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
                    code = OperationTypeCode.of("TRANSFER"),
                    name = OperationTypeName.of("Transfer"),
                    isLeaf = true,
                    createdAt = now,
                ),
            )
            repositories.fundingSources.insert(
                tenantId,
                FundingSource(
                    id = fundingSourceId,
                    tenantId = tenantId,
                    productId = FundingSourceProductId.of(uuid(20)),
                    isActive = true,
                    createdAt = now,
                ),
            )
            ownerIds.forEachIndexed { index, ownerId ->
                repositories.fundingSourceOwners.insert(
                    tenantId,
                    FundingSourceOwner(
                        id = FundingSourceOwnerId.of(uuid(30 + index.toLong())),
                        tenantId = tenantId,
                        partyProfileId = ownerId,
                        fundingSourceId = fundingSourceId,
                        ownerRole = if (index == 0) {
                            FundingSourceOwnerRole.PRIMARY_OWNER
                        } else {
                            FundingSourceOwnerRole.JOINT_OWNER
                        },
                        createdAt = now,
                    ),
                )
            }
            val idGenerator = SequentialIdGenerator(startAt = 1000L)
            service = DefaultLimitEvaluationService(
                transactionRepository = repositories.transactions,
                partyProfileRepository = repositories.partyProfiles,
                tenantRepository = repositories.tenants,
                channelRepository = repositories.channels,
                operationTypeRepository = repositories.operationTypes,
                fundingSourceRepository = repositories.fundingSources,
                fundingSourceOwnerRepository = repositories.fundingSourceOwners,
                transactionFactory = TransactionFactory(idGenerator),
                mandateResolver = MandateResolver(
                    mandateRepository = repositories.mandates,
                    fundingSourceGroupMemberRepository = repositories.fundingSourceGroupMembers,
                ),
                limitRuleMatcher = LimitRuleMatcher(
                    limitRuleRepository = repositories.limitRules,
                    channelGroupMemberRepository = repositories.channelGroupMembers,
                ),
                authorizationEvaluator = AuthorizationEvaluator(
                    UsageReservationService(
                        idGenerator = idGenerator,
                        usageBucketRepository = repositories.usageBuckets,
                        usageEventRepository = repositories.usageEvents,
                        usageWindowCalculator = UsageWindowCalculator(),
                    ),
                ),
                transactionRecorder = TransactionRecorder(
                    transactionRepository = repositories.transactions,
                    transactionEventRepository = repositories.transactionEvents,
                    idGenerator = idGenerator,
                ),
                clockProvider = FixedClockProvider(now),
                transactionRunner = DirectTransactionRunner(),
            )
        }

        fun addMandate(
            id: Long,
            ownerId: PartyProfileId,
            scope: FundingSourceScope,
            isActive: Boolean = true,
        ): Mandate =
            Mandate(
                id = MandateId.of(uuid(id)),
                tenantId = tenantId,
                ownerPartyProfileId = ownerId,
                assigneePartyProfileId = actorId,
                authorityDelegationId = null,
                name = MandateName.of("Mandate $id"),
                description = null,
                fundingScope = scope,
                isActive = isActive,
                version = Version.INITIAL,
                effectiveWindow = EffectiveWindow(now.minusSeconds(60), null),
                metadata = Metadata.EMPTY,
                createdAt = now,
                updatedAt = now,
            ).also { repositories.mandates.insert(tenantId, it) }

        fun addRule(
            id: Long,
            ownerId: PartyProfileId,
            mandateScope: MandateScope,
            amount: String,
            priority: Int = 100,
            channelScope: LimitChannelScope = LimitChannelScope.AllChannels,
            limitKind: LimitKind = LimitKind.PerTransaction,
            usageConsumptionMode: LimitUsageConsumptionMode = LimitUsageConsumptionMode.CASCADING,
        ): LimitRule =
            LimitRule(
                id = LimitRuleId.of(uuid(id)),
                tenantId = tenantId,
                ownerPartyProfileId = ownerId,
                name = LimitRuleName.of("Rule $id"),
                description = null,
                limitKind = limitKind,
                amount = Money.positive(amount, CurrencyCode.of("EUR")),
                mandateScope = mandateScope,
                configurationId = null,
                channelScope = channelScope,
                operationScope = LimitOperationScope.OperationTypeSpecific(operationTypeId),
                usageConsumptionMode = usageConsumptionMode,
                priority = LimitPriority.of(priority),
                isActive = true,
                version = Version.INITIAL,
                effectiveWindow = EffectiveWindow(now.minusSeconds(60), null),
                metadata = Metadata.EMPTY,
                createdAt = now,
                updatedAt = now,
            ).also { repositories.limitRules.insert(tenantId, it) }

        fun periodicBucket(rule: LimitRule, period: LimitPeriod) =
            repositories.usageBuckets.findByLimitRuleAndAssigneeInWindow(
                tenantId = tenantId,
                limitRuleId = rule.id,
                assigneeId = actorId,
                currency = rule.amount.currency,
                window = UsageWindowCalculator().calculateWindow(now, period),
            ).let(::assertNotNull)

        private fun addPartyProfile(profileId: PartyProfileId) {
            repositories.partyProfiles.insert(
                tenantId,
                PartyProfile(
                    id = profileId,
                    partyId = PartyId.of(UUID(profileId.value.mostSignificantBits, profileId.value.leastSignificantBits + 1000)),
                    tenantId = tenantId,
                    createdAt = now,
                ),
            )
        }
    }

    private companion object {
        fun uuid(value: Long): UUID = UUID(0L, value)
    }
}
