package io.github.sepfins.limitguard.application

import io.github.sepfins.limitguard.api.LimitChangeActorType
import io.github.sepfins.limitguard.api.LimitKind
import io.github.sepfins.limitguard.api.LimitRuleChangeStatus
import io.github.sepfins.limitguard.api.LimitMandateScope
import io.github.sepfins.limitguard.api.LimitRuleChangeType
import io.github.sepfins.limitguard.api.PartyType
import io.github.sepfins.limitguard.api.commands.CreateLimitConfigurationCommand
import io.github.sepfins.limitguard.api.commands.CreateLimitChangePolicyCommand
import io.github.sepfins.limitguard.api.commands.CreateMandateCommand
import io.github.sepfins.limitguard.api.commands.ApproveLimitRuleChangeCommand
import io.github.sepfins.limitguard.api.commands.ApplyDueLimitChangesCommand
import io.github.sepfins.limitguard.api.commands.SetLimitConfigurationActiveCommand
import io.github.sepfins.limitguard.api.commands.CancelLimitRuleChangeCommand
import io.github.sepfins.limitguard.api.commands.LimitChangeActorCommand
import io.github.sepfins.limitguard.api.commands.LimitRuleDraft
import io.github.sepfins.limitguard.api.commands.RegisterPartyProfileCommand
import io.github.sepfins.limitguard.api.commands.RegisterChannelCommand
import io.github.sepfins.limitguard.api.commands.RegisterTenantCommand
import io.github.sepfins.limitguard.api.commands.RejectLimitRuleChangeCommand
import io.github.sepfins.limitguard.api.commands.RequestLimitRuleChangeCommand
import io.github.sepfins.limitguard.api.errors.ValidationException
import io.github.sepfins.limitguard.api.errors.IdempotencyConflictException
import io.github.sepfins.limitguard.api.queries.GetLimitChangeQuery
import io.github.sepfins.limitguard.api.queries.GetLimitRuleQuery
import io.github.sepfins.limitguard.domain.change.LimitChangePolicy
import io.github.sepfins.limitguard.domain.change.LimitChangePolicyEngine
import io.github.sepfins.limitguard.domain.change.LimitChangePolicyEvaluationOutcome
import io.github.sepfins.limitguard.domain.common.CurrencyCode
import io.github.sepfins.limitguard.domain.common.Metadata
import io.github.sepfins.limitguard.domain.common.Money
import io.github.sepfins.limitguard.domain.policy.PolicyAction
import io.github.sepfins.limitguard.domain.policy.PolicyEvaluationContext
import io.github.sepfins.limitguard.domain.policy.PolicyEvaluationResult
import io.github.sepfins.limitguard.infrastructure.policy.jsonlogic.JsonLogicPolicyEngine
import io.github.sepfins.limitguard.port.policy.PolicyEngine
import io.github.sepfins.limitguard.port.transactions.TransactionRunner
import io.github.sepfins.limitguard.support.DirectTransactionRunner
import io.github.sepfins.limitguard.support.FixedClockProvider
import io.github.sepfins.limitguard.support.SequentialIdGenerator
import io.github.sepfins.limitguard.support.persistence.InMemoryLimitguardRepositories
import java.math.BigDecimal
import java.math.BigInteger
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ImmutableChangeIntentTest {
    @Test
    fun `creation retries conflict on changes to any original submission field`() {
        val fixture = Fixture()
        val created = fixture.facade.limitChangeService.requestLimitRuleChange(fixture.command)
        val rule = fixture.approve(created.id)
        val channel = fixture.facade.tenantCatalogService.registerChannel(RegisterChannelCommand(fixture.tenant, "MOBILE", "Mobile")).id
        val effectiveAt = fixture.clock.now().plusSeconds(3600)
        val command = fixture.command.copy(
            changeType = LimitRuleChangeType.UPDATE, limitRuleId = rule.limitRuleId, expectedVersion = 1,
            requestedBy = LimitChangeActorCommand(LimitChangeActorType.PARTY_PROFILE, partyProfileId = fixture.owner),
            requestChannelId = channel, effectiveAt = effectiveAt, expiresAt = effectiveAt.plusSeconds(3600),
            policyContext = mapOf("optional" to null), metadata = mapOf("attempt" to 1L), idempotencyKey = "update-intent",
        )
        val pending = fixture.facade.limitChangeService.requestLimitRuleChange(command)
        val evidence = fixture.details(pending.id)
        assertEquals(pending, fixture.facade.limitChangeService.requestLimitRuleChange(command.copy(
            requesterPartyProfileId = fixture.owner, metadata = mapOf("attempt" to BigDecimal("1.00")),
        )))
        val different = listOf(
            command.copy(expectedVersion = 2),
            command.copy(limitRuleId = UUID.randomUUID()),
            command.copy(requesterPartyProfileId = UUID.randomUUID()),
            command.copy(requestChannelId = UUID.randomUUID()),
            command.copy(authorityDelegationId = UUID.randomUUID()),
            command.copy(targetOwnerPartyProfileId = UUID.randomUUID()),
            command.copy(requestedBy = LimitChangeActorCommand(LimitChangeActorType.BANK_WORKER, bankWorkerId = UUID.randomUUID())),
            command.copy(effectiveAt = effectiveAt.plusSeconds(1)),
            command.copy(expiresAt = command.expiresAt!!.plusSeconds(1)),
            command.copy(policyContext = emptyMap()),
            command.copy(metadata = mapOf("attempt" to 2)),
            command.copy(requestedRule = command.requestedRule!!.copy(amount = BigDecimal("501"))),
        )
        for (retry in different) {
            assertFailsWith<IdempotencyConflictException> { fixture.facade.limitChangeService.requestLimitRuleChange(retry) }
        }
        assertEquals(evidence, fixture.details(pending.id))
    }

    @Test
    fun `approval detaches command JSON before entering the host transaction`() {
        var onTransaction: (() -> Unit)? = null
        val runner = object : TransactionRunner {
            override fun <T> inTransaction(block: () -> T): T {
                onTransaction?.invoke()
                return block()
            }
            override fun <T> inSavepoint(block: () -> T): T = block()
        }
        val fixture = Fixture(transactionRunner = runner)
        val pending = fixture.facade.limitChangeService.requestLimitRuleChange(fixture.command)
        val labels = mutableListOf<Any?>("submitted", null)
        val metadata = mapOf("labels" to labels)
        onTransaction = { labels[0] = "changed while waiting" }
        fixture.facade.limitChangeService.approveLimitRuleChange(ApproveLimitRuleChangeCommand(
            fixture.tenant, pending.id, LimitChangeActorCommand(LimitChangeActorType.SYSTEM),
            approvedRule = fixture.command.requestedRule!!.copy(metadata = metadata), metadata = metadata,
        ))
        onTransaction = null
        val details = fixture.details(pending.id)
        val expected = mapOf("labels" to listOf("submitted", null))
        assertEquals(expected, details.approvedRule!!["metadata"])
        assertEquals(expected, details.events.single { it.eventType.name == "APPROVED" }.payload)
    }

    @Test
    fun `creation replay retains original intent after cap delay application and retirement`() {
        val fixture = Fixture(listOf(JsonLogicPolicyEngine()))
        val dueAt = fixture.clock.now().plusSeconds(60)
        fixture.facade.tenantCatalogService.createLimitChangePolicy(CreateLimitChangePolicyCommand(
            tenantId = fixture.tenant, code = "CAP_AND_DELAY", name = "Cap and delay",
            phase = io.github.sepfins.limitguard.api.LimitChangePolicyPhase.AUTHORIZATION,
            decision = io.github.sepfins.limitguard.api.LimitChangePolicyDecision.ALLOW,
            conditionExpression = mapOf("==" to listOf(mapOf("var" to "changeRequest.changeType"), "CREATE")),
            actionExpression = listOf(
                mapOf("type" to "CAP_REQUESTED_AMOUNT", "amount" to "400.00"),
                mapOf("type" to "SET_EFFECTIVE_AT", "instant" to dueAt.toString()),
            ),
        ))
        val command = fixture.command.copy(expiresAt = dueAt.plusSeconds(60))
        val scheduled = fixture.facade.limitChangeService.requestLimitRuleChange(command)
        assertEquals(LimitRuleChangeStatus.PENDING_EFFECTIVE_DATE, scheduled.status)
        assertEquals(scheduled, fixture.facade.limitChangeService.requestLimitRuleChange(command))
        assertFailsWith<IdempotencyConflictException> {
            fixture.facade.limitChangeService.requestLimitRuleChange(command.copy(effectiveAt = dueAt))
        }
        assertFailsWith<IdempotencyConflictException> {
            fixture.facade.limitChangeService.requestLimitRuleChange(command.copy(
                requestedRule = command.requestedRule!!.copy(amount = BigDecimal("400")),
            ))
        }

        fixture.clock.setTo(dueAt)
        assertEquals(1, fixture.facade.limitChangeService.applyDueLimitChanges(ApplyDueLimitChangesCommand(fixture.tenant)).appliedCount)
        val applied = fixture.facade.limitChangeService.requestLimitRuleChange(command)
        val ruleId = applied.limitRuleId!!
        assertEquals(LimitRuleChangeStatus.APPLIED, applied.status)
        assertEquals(BigDecimal("400.0000"), fixture.facade.limitInquiryService.getLimitRule(GetLimitRuleQuery(fixture.tenant, ruleId)).amount)

        val retire = fixture.facade.limitChangeService.requestLimitRuleChange(command.copy(
            changeType = LimitRuleChangeType.DELETE, limitRuleId = ruleId, expectedVersion = 1,
            requestedRule = null, expiresAt = null, idempotencyKey = "retire",
        ))
        fixture.approve(retire.id)
        fixture.facade.tenantCatalogService.setLimitConfigurationActive(SetLimitConfigurationActiveCommand(
            fixture.tenant, fixture.configuration, false,
        ))
        fixture.clock.setTo(dueAt.plusSeconds(3600))
        val details = fixture.details(applied.id)
        assertEquals(applied, fixture.facade.limitChangeService.requestLimitRuleChange(command))
        assertEquals(details, fixture.details(applied.id))
    }

    @Test
    fun `omitted draft defaults are compared with their first resolved values after the rule changes`() {
        val fixture = Fixture()
        val created = fixture.facade.limitChangeService.requestLimitRuleChange(fixture.command)
        val applied = fixture.approve(created.id)
        val originalStart = fixture.clock.now()
        fixture.clock.setTo(originalStart.plusSeconds(10))
        val update = fixture.command.copy(
            changeType = LimitRuleChangeType.UPDATE, limitRuleId = applied.limitRuleId, expectedVersion = 1,
            idempotencyKey = "update", requestedRule = fixture.command.requestedRule!!.copy(configurationId = null),
        )
        val pending = fixture.facade.limitChangeService.requestLimitRuleChange(update)
        val otherConfiguration = fixture.facade.tenantCatalogService.createLimitConfiguration(CreateLimitConfigurationCommand(
            tenantId = fixture.tenant, code = "OTHER", name = "Other limit", limitKind = LimitKind.PER_TRANSACTION,
            currency = "EUR", maxAssignableAmount = BigDecimal("1000"),
        )).id
        val adjusted = update.requestedRule!!.copy(configurationId = otherConfiguration, startsAt = fixture.clock.now())
        val changed = fixture.facade.limitChangeService.approveLimitRuleChange(ApproveLimitRuleChangeCommand(
            fixture.tenant, pending.id, LimitChangeActorCommand(LimitChangeActorType.SYSTEM), adjusted,
        ))
        val beforeReplay = fixture.details(pending.id)
        assertEquals(changed, fixture.facade.limitChangeService.requestLimitRuleChange(update))
        assertEquals(changed, fixture.facade.limitChangeService.requestLimitRuleChange(update.copy(
            requestedRule = update.requestedRule.copy(configurationId = fixture.configuration, startsAt = originalStart),
        )))
        assertFailsWith<IdempotencyConflictException> {
            fixture.facade.limitChangeService.requestLimitRuleChange(update.copy(requestedRule = adjusted))
        }
        assertEquals(beforeReplay, fixture.details(pending.id))
    }

    @Test
    fun `creation replay compares the original mandate target after adjusted approval`() {
        val fixture = Fixture()
        val assignee = fixture.facade.tenantCatalogService.registerPartyProfile(RegisterPartyProfileCommand(
            tenantId = fixture.tenant, partyType = PartyType.INDIVIDUAL, firstName = "Sam", lastName = "Delegate",
        )).id
        val mandate = fixture.facade.tenantCatalogService.createMandate(CreateMandateCommand(
            fixture.tenant, fixture.owner, assignee, "Delegate mandate",
        )).id
        val submitted = fixture.facade.limitChangeService.requestLimitRuleChange(fixture.command)
        val adjusted = fixture.command.requestedRule!!.copy(
            mandateScope = LimitMandateScope.MANDATE, mandateId = mandate, amount = BigDecimal("450"),
        )
        val applied = fixture.facade.limitChangeService.approveLimitRuleChange(ApproveLimitRuleChangeCommand(
            fixture.tenant, submitted.id, LimitChangeActorCommand(LimitChangeActorType.SYSTEM), adjusted,
        ))
        val details = fixture.facade.limitInquiryService.getLimitChange(GetLimitChangeQuery(fixture.tenant, submitted.id))

        assertEquals(mandate, applied.targetMandateId)
        assertEquals(applied, fixture.facade.limitChangeService.requestLimitRuleChange(fixture.command))
        assertFailsWith<IdempotencyConflictException> {
            fixture.facade.limitChangeService.requestLimitRuleChange(fixture.command.copy(requestedRule = adjusted))
        }
        assertEquals(details, fixture.facade.limitInquiryService.getLimitChange(GetLimitChangeQuery(fixture.tenant, submitted.id)))
    }

    @Test
    fun `policy definitions engine input and action evidence are detached snapshots`() {
        var seenActions: List<Map<String, Any?>> = emptyList()
        var inputMutationBlocked = false
        val engineOutput = mutableListOf<Any?>("original outcome", null)
        val engine = object : PolicyEngine {
            override val engine = LimitChangePolicyEngine.JSON_LOGIC
            override fun evaluate(policy: LimitChangePolicy, context: PolicyEvaluationContext): PolicyEvaluationResult {
                seenActions = policy.actionExpression
                inputMutationBlocked = runCatching {
                    (context.changeRequest.policyContext.value["labels"] as MutableList<*>).clear()
                }.exceptionOrNull() is UnsupportedOperationException
                return PolicyEvaluationResult(
                    matched = true, outcome = LimitChangePolicyEvaluationOutcome.APPLIED, decision = policy.decision,
                    actions = listOf(PolicyAction.CapRequestedAmount(Money.positive("400", CurrencyCode.of("EUR")))),
                    actionResult = Metadata(mapOf("output" to engineOutput)),
                )
            }
        }
        val fixture = Fixture(listOf(engine))
        val amountExpression = mutableMapOf<String, Any?>("var" to "facts.cap")
        val action = mutableMapOf<String, Any?>("type" to "CAP_REQUESTED_AMOUNT", "amount" to amountExpression)
        val actions = mutableListOf<Map<String, Any?>>(action)
        val policy = fixture.facade.tenantCatalogService.createLimitChangePolicy(CreateLimitChangePolicyCommand(
            tenantId = fixture.tenant, code = "CAP", name = "Cap", phase = io.github.sepfins.limitguard.api.LimitChangePolicyPhase.AUTHORIZATION,
            decision = io.github.sepfins.limitguard.api.LimitChangePolicyDecision.REQUIRE_APPROVAL,
            actionExpression = actions,
        ))
        amountExpression["var"] = "facts.changed"
        action["type"] = "changed"
        actions.clear()
        val result = fixture.facade.limitChangeService.requestLimitRuleChange(fixture.command.copy(
            policyContext = mapOf("labels" to mutableListOf("original")),
        ))
        engineOutput[0] = "changed outcome"

        val expectedActions = listOf(mapOf("type" to "CAP_REQUESTED_AMOUNT", "amount" to mapOf("var" to "facts.cap")))
        assertEquals(expectedActions, policy.actionExpression)
        assertEquals(expectedActions, seenActions)
        assertTrue(inputMutationBlocked)
        assertFailsWith<UnsupportedOperationException> { (policy.actionExpression as MutableList<*>).clear() }
        assertFailsWith<UnsupportedOperationException> {
            (policy.actionExpression[0]["amount"] as MutableMap<*, *>).clear()
        }
        val details = fixture.facade.limitInquiryService.getLimitChange(GetLimitChangeQuery(fixture.tenant, result.id))
        val actionResult = details.events.last().payload["actionResult"] as Map<*, *>
        assertEquals(listOf("original outcome", null), actionResult["output"])
        assertFailsWith<UnsupportedOperationException> { (actionResult["output"] as MutableList<*>).clear() }
    }

    @Test
    fun `malformed JSON is rejected before admission and before replay comparison`() {
        val cycle = mutableListOf<Any?>()
        cycle.add(cycle)
        val malformed = listOf(
            cycle, mapOf(1 to "non-string key"), Double.NaN, Float.POSITIVE_INFINITY,
            AtomicInteger(1), Instant.EPOCH, setOf("not a JSON array"), arrayOf("not a JSON list"),
            object : BigInteger("1") {
                override fun toByte(): Byte = 1
                override fun toShort(): Short = 1
            },
            object : BigDecimal("1.0") {
                override fun toByte(): Byte = 1
                override fun toShort(): Short = 1
            },
        )
        for (value in malformed) {
            for (boundary in listOf("metadata", "policyContext", "draft")) {
                val fixture = Fixture()
                val invalid = mapOf("bad" to value)
                val command = when (boundary) {
                    "metadata" -> fixture.command.copy(metadata = invalid)
                    "policyContext" -> fixture.command.copy(policyContext = invalid)
                    else -> fixture.command.copy(requestedRule = fixture.command.requestedRule!!.copy(metadata = invalid))
                }
                assertFailsWith<ValidationException>(boundary) {
                    fixture.facade.limitChangeService.requestLimitRuleChange(command)
                }
                val admitted = fixture.facade.limitChangeService.requestLimitRuleChange(fixture.command)
                assertEquals(LimitRuleChangeStatus.REQUESTED, admitted.status)
                assertFailsWith<ValidationException>("$boundary replay") {
                    fixture.facade.limitChangeService.requestLimitRuleChange(command)
                }
                assertEquals(admitted, fixture.facade.limitChangeService.requestLimitRuleChange(fixture.command))
            }
        }
    }

    @Test
    fun `invalid decision metadata cannot partially reject or cancel a request`() {
        for (cancel in listOf(false, true)) {
            val fixture = Fixture()
            val result = fixture.facade.limitChangeService.requestLimitRuleChange(fixture.command)
            val actor = LimitChangeActorCommand(LimitChangeActorType.SYSTEM)
            assertFailsWith<ValidationException> {
                if (cancel) {
                    fixture.facade.limitChangeService.cancelLimitRuleChange(CancelLimitRuleChangeCommand(
                        fixture.tenant, result.id, actor, metadata = mapOf("bad" to Double.NaN),
                    ))
                } else {
                    fixture.facade.limitChangeService.rejectLimitRuleChange(RejectLimitRuleChangeCommand(
                        fixture.tenant, result.id, actor, "Declined", metadata = mapOf("bad" to Double.NaN),
                    ))
                }
            }
            val details = fixture.facade.limitInquiryService.getLimitChange(GetLimitChangeQuery(fixture.tenant, result.id))
            assertEquals(LimitRuleChangeStatus.REQUESTED, details.request.status)
            assertEquals(1, details.events.size)
        }
    }

    @Test
    fun `caller mutation cannot change submitted intent or returned evidence`() {
        val fixture = Fixture()
        val labels = mutableListOf<Any?>("original", null)
        val nested = mutableMapOf<String, Any?>("labels" to labels)
        val metadata = mutableMapOf<String, Any?>("nested" to nested)
        val command = fixture.command.copy(
            metadata = metadata,
            policyContext = metadata,
            requestedRule = fixture.command.requestedRule!!.copy(metadata = metadata),
        )
        val result = fixture.facade.limitChangeService.requestLimitRuleChange(command)

        labels[0] = "changed"
        nested["extra"] = true
        metadata.clear()

        val expected = mapOf("nested" to mapOf("labels" to listOf("original", null)))
        val details = fixture.facade.limitInquiryService.getLimitChange(GetLimitChangeQuery(fixture.tenant, result.id))
        assertEquals(expected, result.metadata)
        assertEquals(expected, details.request.metadata)
        assertEquals(expected, details.requestedRule["metadata"])
        assertFailsWith<UnsupportedOperationException> {
            (result.metadata as MutableMap<String, Any?>)["extra"] = true
        }
        assertFailsWith<UnsupportedOperationException> {
            ((result.metadata["nested"] as Map<*, *>)["labels"] as MutableList<*>).clear()
        }
        val replay = fixture.facade.limitChangeService.requestLimitRuleChange(command.copy(
            metadata = expected,
            policyContext = expected,
            requestedRule = command.requestedRule!!.copy(metadata = expected),
        ))
        assertEquals(result, replay)
        assertEquals(details, fixture.facade.limitInquiryService.getLimitChange(GetLimitChangeQuery(fixture.tenant, result.id)))
    }

    private class Fixture(
        engines: Collection<PolicyEngine> = emptyList(),
        transactionRunner: TransactionRunner = DirectTransactionRunner(),
    ) {
        val clock = FixedClockProvider(Instant.parse("2026-06-26T12:00:00Z"))
        private val repositories = InMemoryLimitguardRepositories()
        val facade = DefaultLimitguard(
            tenantRepository = repositories.tenants,
            identifierSchemeRepository = repositories.identifierSchemes,
            entityIdentifierRepository = repositories.entityIdentifiers,
            channelRepository = repositories.channels,
            channelGroupRepository = repositories.channelGroups,
            channelGroupMemberRepository = repositories.channelGroupMembers,
            operationTypeRepository = repositories.operationTypes,
            partyRepository = repositories.parties,
            individualRepository = repositories.individuals,
            legalEntityRepository = repositories.legalEntities,
            partyLegalIdentifierRepository = repositories.partyLegalIdentifiers,
            partyProfileRepository = repositories.partyProfiles,
            partyProfileDelegationRepository = repositories.partyProfileDelegations,
            bankWorkerRepository = repositories.bankWorkers,
            fundingSourceRepository = repositories.fundingSources,
            fundingSourceProductRepository = repositories.fundingSourceProducts,
            fundingSourceOwnerRepository = repositories.fundingSourceOwners,
            fundingSourceGroupRepository = repositories.fundingSourceGroups,
            fundingSourceGroupMemberRepository = repositories.fundingSourceGroupMembers,
            mandateRepository = repositories.mandates,
            limitConfigurationRepository = repositories.limitConfigurations,
            limitChangePolicyRepository = repositories.limitChangePolicies,
            limitChangePolicyEvaluationRepository = repositories.limitChangePolicyEvaluations,
            limitChangeRequestRepository = repositories.limitChangeRequests,
            limitChangeEventRepository = repositories.limitRuleChangeEvents,
            limitRuleRepository = repositories.limitRules,
            limitRuleRevisionRepository = repositories.limitRuleRevisions,
            authorizationDecisionRepository = repositories.authorizationDecisions,
            transactionRepository = repositories.transactions,
            transactionEventRepository = repositories.transactionEvents,
            usageBucketRepository = repositories.usageBuckets,
            usageEventRepository = repositories.usageEvents,
            policyEngines = engines,
            idGenerator = SequentialIdGenerator(),
            clockProvider = clock,
            transactionRunner = transactionRunner,
        )
        val tenant = facade.tenantCatalogService.registerTenant(RegisterTenantCommand("Immutable intent bank")).id
        val owner = facade.tenantCatalogService.registerPartyProfile(RegisterPartyProfileCommand(
            tenantId = tenant, partyType = PartyType.INDIVIDUAL, firstName = "Alex", lastName = "Customer",
        )).id
        val configuration = facade.tenantCatalogService.createLimitConfiguration(CreateLimitConfigurationCommand(
            tenantId = tenant, code = "GENERAL", name = "General limit", limitKind = LimitKind.PER_TRANSACTION,
            currency = "EUR", maxAssignableAmount = BigDecimal("1000"),
        )).id
        val command = RequestLimitRuleChangeCommand(
            tenantId = tenant,
            changeType = LimitRuleChangeType.CREATE,
            requestedBy = LimitChangeActorCommand(LimitChangeActorType.SYSTEM),
            targetOwnerPartyProfileId = owner,
            requestedRule = LimitRuleDraft(
                name = "Spending limit", limitKind = LimitKind.PER_TRANSACTION, amount = BigDecimal("500"),
                currency = "EUR", configurationId = configuration,
            ),
            idempotencyKey = "original-submission",
        )

        fun approve(id: UUID) = facade.limitChangeService.approveLimitRuleChange(ApproveLimitRuleChangeCommand(
            tenant, id, LimitChangeActorCommand(LimitChangeActorType.SYSTEM),
        ))

        fun details(id: UUID) = facade.limitInquiryService.getLimitChange(GetLimitChangeQuery(tenant, id))
    }
}
