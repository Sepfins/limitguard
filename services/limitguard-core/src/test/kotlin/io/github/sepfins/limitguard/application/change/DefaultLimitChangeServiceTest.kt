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
import io.github.sepfins.limitguard.domain.common.FundingSourceProductId
import io.github.sepfins.limitguard.domain.common.IdempotencyKey
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
import io.github.sepfins.limitguard.domain.funding.FundingSourceCategory
import io.github.sepfins.limitguard.domain.mandate.Mandate
import io.github.sepfins.limitguard.domain.mandate.MandateName
import io.github.sepfins.limitguard.domain.mandate.MandateScope
import io.github.sepfins.limitguard.infrastructure.policy.jsonlogic.JsonLogicPolicyEngine
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
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DefaultLimitChangeServiceTest {
    private val now = Instant.parse("2026-06-26T12:00:00Z")
    private val tenantId = TenantId.of(UUID.fromString("00000000-0000-0000-0000-000000000001"))
    private val ownerId = UUID.fromString("00000000-0000-0000-0000-000000000002")
    private val requesterId = UUID.fromString("00000000-0000-0000-0000-000000000003")
    private val channelId = UUID.fromString("00000000-0000-0000-0000-000000000004")
    private val configurationId = LimitConfigurationId.of(UUID.fromString("00000000-0000-0000-0000-000000000005"))
    private val otherConfigurationId =
        LimitConfigurationId.of(UUID.fromString("00000000-0000-0000-0000-000000000013"))
    private val childConfigurationId =
        LimitConfigurationId.of(UUID.fromString("00000000-0000-0000-0000-000000000014"))
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
    fun `independent product rules can be applied with the same other dimensions`() {
        val repositories = repositoriesWithConfiguration()
        val firstConfiguration = assertNotNull(repositories.limitConfigurations.findById(tenantId, configurationId))
        repositories.limitConfigurations.update(tenantId, firstConfiguration.copy(
            fundingScope = LimitConfigurationFundingScope.FundingSourceProductSpecific(FundingSourceProductId.of(UUID(0, 50))),
        ))
        insertConfiguration(repositories, otherConfigurationId, fundingScope =
            LimitConfigurationFundingScope.FundingSourceProductSpecific(FundingSourceProductId.of(UUID(0, 51))))
        val service = limitChangeService(repositories)

        val first = service.requestLimitRuleChange(command(idempotencyKey = "product-one"))
        val second = service.requestLimitRuleChange(command(idempotencyKey = "product-two").copy(
            requestedRule = draft(name = "Second product limit").copy(configurationId = otherConfigurationId.value),
        ))

        assertEquals(LimitRuleChangeStatus.APPLIED, first.status)
        assertEquals(LimitRuleChangeStatus.APPLIED, second.status)
        assertNotEquals(first.targetScopeHash, second.targetScopeHash)
        assertEquals(2, repositories.limitRules.findByOwner(tenantId, PartyProfileId.of(ownerId)).size)
    }

    @Test
    fun `configuration identity cannot bypass same funding target conflicts`() {
        val scopes = listOf(
            LimitConfigurationFundingScope.AllFundingSources,
            LimitConfigurationFundingScope.FundingSourceCategorySpecific(FundingSourceCategory.TRANSACTIONAL),
            LimitConfigurationFundingScope.FundingSourceProductSpecific(FundingSourceProductId.of(UUID(0, 50))),
        )
        for (scope in scopes) {
            for (decision in listOf(null, LimitChangePolicyDecision.ALLOW)) {
                val repositories = repositoriesWithConfiguration(authorizationDecision = decision)
                setFundingScope(repositories, scope)
                insertConfiguration(repositories, otherConfigurationId, fundingScope = scope)
                val service = limitChangeService(repositories)
                service.requestLimitRuleChange(command(idempotencyKey = "same-scope-one"))

                assertFailsWith<ConflictException>("$scope / $decision") {
                    service.requestLimitRuleChange(command(idempotencyKey = "same-scope-two").copy(
                        requestedRule = draft(name = "Another configuration").copy(configurationId = otherConfigurationId.value),
                    ))
                }
            }
        }
    }

    @Test
    fun `category targets and a broad all-funding target can coexist`() {
        val repositories = repositoriesWithConfiguration()
        insertConfiguration(repositories, otherConfigurationId, fundingScope =
            LimitConfigurationFundingScope.FundingSourceCategorySpecific(FundingSourceCategory.TRANSACTIONAL))
        insertConfiguration(repositories, childConfigurationId, fundingScope =
            LimitConfigurationFundingScope.FundingSourceCategorySpecific(FundingSourceCategory.SAVINGS))
        val service = limitChangeService(repositories)
        val results = listOf(configurationId, otherConfigurationId, childConfigurationId).mapIndexed { index, id ->
            service.requestLimitRuleChange(command(idempotencyKey = "category-$index").copy(
                requestedRule = draft(name = "Category limit $index").copy(configurationId = id.value),
            ))
        }

        assertEquals(3, results.map { it.targetScopeHash }.toSet().size)
        assertTrue(results.all { it.status == LimitRuleChangeStatus.APPLIED })
    }

    @Test
    fun `product requests retain their target identity through approval`() {
        val repositories = repositoriesWithConfiguration(authorizationDecision = null)
        setFundingScope(repositories,
            LimitConfigurationFundingScope.FundingSourceProductSpecific(FundingSourceProductId.of(UUID(0, 50))))
        insertConfiguration(repositories, otherConfigurationId, fundingScope =
            LimitConfigurationFundingScope.FundingSourceProductSpecific(FundingSourceProductId.of(UUID(0, 51))))
        val service = limitChangeService(repositories)
        val first = service.requestLimitRuleChange(command(idempotencyKey = "first-pending"))
        val second = service.requestLimitRuleChange(command(idempotencyKey = "other-pending").copy(
            requestedRule = draft(name = "Other product").copy(configurationId = otherConfigurationId.value),
        ))
        assertEquals(LimitRuleChangeStatus.REQUESTED, first.status)
        assertEquals(LimitRuleChangeStatus.REQUESTED, second.status)
        assertNotEquals(first.targetScopeHash, second.targetScopeHash)
        assertFailsWith<ConflictException> {
            service.requestLimitRuleChange(command(idempotencyKey = "same-product-target"))
        }

        val approved = service.approveLimitRuleChange(ApproveLimitRuleChangeCommand(
            tenantId = tenantId.value, changeRequestId = first.id,
            approvedBy = LimitChangeActorCommand(LimitChangeActorType.SYSTEM),
        ))
        assertEquals(LimitRuleChangeStatus.APPLIED, approved.status)
        assertEquals(first.targetScopeHash, approved.targetScopeHash)
    }

    @Test
    fun `scheduled product requests apply independently within the batch limit`() {
        val repositories = repositoriesWithConfiguration()
        setFundingScope(repositories,
            LimitConfigurationFundingScope.FundingSourceProductSpecific(FundingSourceProductId.of(UUID(0, 50))))
        insertConfiguration(repositories, otherConfigurationId, fundingScope =
            LimitConfigurationFundingScope.FundingSourceProductSpecific(FundingSourceProductId.of(UUID(0, 51))))
        val service = limitChangeService(repositories)
        val first = service.requestLimitRuleChange(command(idempotencyKey = "first-due", effectiveAt = now.plusSeconds(60)))
        val second = service.requestLimitRuleChange(command(idempotencyKey = "second-due", effectiveAt = now.plusSeconds(60)).copy(
            requestedRule = draft(name = "Other product").copy(configurationId = otherConfigurationId.value),
        ))

        val applyBatch = ApplyDueLimitChangesCommand(tenantId.value, now.plusSeconds(60), limit = 1)
        val batchOne = service.applyDueLimitChanges(applyBatch)
        assertEquals(1, batchOne.appliedCount)
        assertEquals(0, batchOne.failedCount)
        assertEquals(1, repositories.limitRules.findByOwner(tenantId, PartyProfileId.of(ownerId)).size)

        val batchTwo = service.applyDueLimitChanges(applyBatch)
        assertEquals(1, batchTwo.appliedCount)
        assertEquals(0, batchTwo.failedCount)
        assertEquals(setOf(first.id, second.id), (batchOne.appliedChangeRequestIds + batchTwo.appliedChangeRequestIds).toSet())
        assertEquals(2, repositories.limitRules.findByOwner(tenantId, PartyProfileId.of(ownerId)).size)
    }

    @Test
    fun `approval cannot move a product request onto another pending target`() {
        val repositories = repositoriesWithConfiguration(authorizationDecision = null)
        setFundingScope(repositories,
            LimitConfigurationFundingScope.FundingSourceProductSpecific(FundingSourceProductId.of(UUID(0, 50))))
        insertConfiguration(repositories, otherConfigurationId, fundingScope =
            LimitConfigurationFundingScope.FundingSourceProductSpecific(FundingSourceProductId.of(UUID(0, 51))))
        val service = limitChangeService(repositories)
        val first = service.requestLimitRuleChange(command(idempotencyKey = "adjust-one"))
        service.requestLimitRuleChange(command(idempotencyKey = "adjust-two").copy(
            requestedRule = draft(name = "Other product").copy(configurationId = otherConfigurationId.value),
        ))

        assertFailsWith<ConflictException> {
            service.approveLimitRuleChange(ApproveLimitRuleChangeCommand(
                tenantId = tenantId.value, changeRequestId = first.id,
                approvedBy = LimitChangeActorCommand(LimitChangeActorType.SYSTEM),
                approvedRule = draft().copy(configurationId = otherConfigurationId.value),
            ))
        }
    }

    @Test
    fun `unconfigured rules share the all-funding target`() {
        val repositories = repositoriesWithConfiguration()
        val rule = insertRule(repositories)
        repositories.limitRules.update(tenantId, rule.copy(configurationId = null))

        assertFailsWith<ConflictException> {
            limitChangeService(repositories).requestLimitRuleChange(command(name = "Configured all funding"))
        }
    }

    private fun setFundingScope(repositories: InMemoryLimitguardRepositories, scope: LimitConfigurationFundingScope) {
        val configuration = assertNotNull(repositories.limitConfigurations.findById(tenantId, configurationId))
        repositories.limitConfigurations.update(tenantId, configuration.copy(fundingScope = scope))
    }

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
    fun `configuration-bound policy applies to its exact configuration`() {
        val repositories = repositoriesWithConfiguration(authorizationDecision = null)
        repositories.limitChangePolicies.insert(
            tenantId,
            authorizationPolicy(
                decision = LimitChangePolicyDecision.ALLOW,
                limitConfigurationId = configurationId,
            ),
        )
        val service = limitChangeService(repositories)

        val result = service.requestLimitRuleChange(command(idempotencyKey = "bound-a"))

        assertEquals(LimitRuleChangeStatus.APPLIED, result.status)
        assertEquals(
            listOf(authorizationPolicyId),
            repositories.limitChangePolicyEvaluations
                .findByChangeRequest(tenantId, LimitChangeRequestId.of(result.id))
                .map { it.policyId },
        )
    }

    @Test
    fun `configuration-bound policy is not evaluated for another configuration`() {
        val repositories = repositoriesWithConfiguration(authorizationDecision = null)
        insertConfiguration(repositories, otherConfigurationId)
        repositories.limitChangePolicies.insert(
            tenantId,
            authorizationPolicy(
                decision = LimitChangePolicyDecision.ALLOW,
                limitConfigurationId = configurationId,
            ),
        )
        val service = limitChangeService(repositories)

        val result = service.requestLimitRuleChange(
            command(idempotencyKey = "bound-b").copy(
                requestedRule = draft().copy(configurationId = otherConfigurationId.value),
            )
        )

        assertEquals(LimitRuleChangeStatus.REQUESTED, result.status)
        assertEquals(null, result.limitRuleId)
        assertEquals(
            emptyList(),
            repositories.limitChangePolicyEvaluations
                .findByChangeRequest(tenantId, LimitChangeRequestId.of(result.id)),
        )
    }

    @Test
    fun `unbound policy applies across configurations`() {
        val repositories = repositoriesWithConfiguration(authorizationDecision = null)
        insertConfiguration(repositories, otherConfigurationId)
        repositories.limitChangePolicies.insert(
            tenantId,
            authorizationPolicy(decision = LimitChangePolicyDecision.ALLOW),
        )
        val service = limitChangeService(repositories)

        val result = service.requestLimitRuleChange(
            command(idempotencyKey = "unbound-b").copy(
                requestedRule = draft().copy(configurationId = otherConfigurationId.value),
            )
        )

        assertEquals(LimitRuleChangeStatus.APPLIED, result.status)
        assertEquals(
            listOf(authorizationPolicyId),
            repositories.limitChangePolicyEvaluations
                .findByChangeRequest(tenantId, LimitChangeRequestId.of(result.id))
                .map { it.policyId },
        )
    }

    @Test
    fun `configuration-bound denial does not reject another configuration`() {
        val repositories = repositoriesWithConfiguration(authorizationDecision = null)
        insertConfiguration(repositories, otherConfigurationId)
        repositories.limitChangePolicies.insert(
            tenantId,
            authorizationPolicy(
                decision = LimitChangePolicyDecision.DENY,
                limitConfigurationId = configurationId,
            ),
        )
        val service = limitChangeService(repositories)

        val result = service.requestLimitRuleChange(
            command(idempotencyKey = "deny-other-config").copy(
                requestedRule = draft().copy(configurationId = otherConfigurationId.value),
            )
        )

        assertEquals(LimitRuleChangeStatus.REQUESTED, result.status)
        assertEquals(null, result.limitRuleId)
        assertEquals(
            emptyList(),
            repositories.limitChangePolicyEvaluations
                .findByChangeRequest(tenantId, LimitChangeRequestId.of(result.id)),
        )
    }

    @Test
    fun `configuration filtering happens before priority among applicable policies`() {
        val repositories = repositoriesWithConfiguration(authorizationDecision = null)
        insertConfiguration(repositories, otherConfigurationId)
        repositories.limitChangePolicies.insert(
            tenantId,
            authorizationPolicy(
                decision = LimitChangePolicyDecision.REQUIRE_APPROVAL,
                id = reviewPolicyId,
                code = "REVIEW_CONFIGURATION_A",
                priority = 1,
                limitConfigurationId = configurationId,
            ),
        )
        repositories.limitChangePolicies.insert(
            tenantId,
            authorizationPolicy(
                decision = LimitChangePolicyDecision.ALLOW,
                priority = 100,
            ),
        )
        val service = limitChangeService(repositories)

        val result = service.requestLimitRuleChange(
            command(idempotencyKey = "priority-other-config").copy(
                requestedRule = draft().copy(configurationId = otherConfigurationId.value),
            )
        )

        assertEquals(LimitRuleChangeStatus.APPLIED, result.status)
        assertEquals(
            listOf(authorizationPolicyId),
            repositories.limitChangePolicyEvaluations
                .findByChangeRequest(tenantId, LimitChangeRequestId.of(result.id))
            .map { it.policyId },
        )
    }

    @Test
    fun `applicable configuration-bound policies retain priority order`() {
        val repositories = repositoriesWithConfiguration(authorizationDecision = null)
        repositories.limitChangePolicies.insert(
            tenantId,
            authorizationPolicy(
                decision = LimitChangePolicyDecision.REQUIRE_APPROVAL,
                id = reviewPolicyId,
                code = "REVIEW_CONFIGURATION_A",
                priority = 1,
                limitConfigurationId = configurationId,
            ),
        )
        repositories.limitChangePolicies.insert(
            tenantId,
            authorizationPolicy(
                decision = LimitChangePolicyDecision.ALLOW,
                priority = 100,
                limitConfigurationId = configurationId,
            ),
        )
        val service = limitChangeService(repositories)

        val result = service.requestLimitRuleChange(command(idempotencyKey = "priority-bound-a"))

        assertEquals(LimitRuleChangeStatus.REQUESTED, result.status)
        assertEquals(
            listOf(reviewPolicyId),
            repositories.limitChangePolicyEvaluations
                .findByChangeRequest(tenantId, LimitChangeRequestId.of(result.id))
                .map { it.policyId },
        )
    }

    @Test
    fun `existing rule change uses its configuration when command has no draft`() {
        val repositories = repositoriesWithConfiguration(authorizationDecision = null)
        val existingRule = insertRule(repositories)
        repositories.limitChangePolicies.insert(
            tenantId,
            authorizationPolicy(
                decision = LimitChangePolicyDecision.ALLOW,
                limitConfigurationId = configurationId,
            ),
        )
        val service = limitChangeService(repositories)

        val result = service.requestLimitRuleChange(
            commandForExistingRule(
                changeType = LimitRuleChangeType.DEACTIVATE,
                currentRule = existingRule,
                idempotencyKey = "deactivate-bound-rule",
            )
        )

        assertEquals(LimitRuleChangeStatus.APPLIED, result.status)
        assertEquals(
            listOf(authorizationPolicyId),
            repositories.limitChangePolicyEvaluations
                .findByChangeRequest(tenantId, LimitChangeRequestId.of(result.id))
                .map { it.policyId },
        )
        assertEquals(false, repositories.limitRules.findById(tenantId, existingRule.id)?.isActive)
    }

    @Test
    fun `parent configuration policy does not apply to a child configuration`() {
        val repositories = repositoriesWithConfiguration(authorizationDecision = null)
        insertConfiguration(
            repositories,
            id = childConfigurationId,
            parentConfigurationId = configurationId,
            maxAssignableAmount = "900.00",
        )
        repositories.limitChangePolicies.insert(
            tenantId,
            authorizationPolicy(
                decision = LimitChangePolicyDecision.ALLOW,
                limitConfigurationId = configurationId,
            ),
        )
        val service = limitChangeService(repositories)

        val result = service.requestLimitRuleChange(
            command(idempotencyKey = "child-configuration").copy(
                requestedRule = draft().copy(configurationId = childConfigurationId.value),
            )
        )

        assertEquals(LimitRuleChangeStatus.REQUESTED, result.status)
        assertEquals(null, result.limitRuleId)
        assertEquals(
            emptyList(),
            repositories.limitChangePolicyEvaluations
                .findByChangeRequest(tenantId, LimitChangeRequestId.of(result.id)),
        )
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
    fun `matching JSON Logic policy by priority controls authorization`() {
        val repositories = repositoriesWithConfiguration(LimitChangePolicyDecision.ALLOW)
        repositories.limitChangePolicies.insert(
            tenantId,
            authorizationPolicy(
                decision = LimitChangePolicyDecision.REQUIRE_APPROVAL,
                id = reviewPolicyId,
                code = "REVIEW_LIMIT_CHANGE",
                priority = 10,
                conditionExpression = amountGreaterThan("600.00"),
            ),
        )
        val service = limitChangeService(repositories)

        val result = service.requestLimitRuleChange(command(amount = "700.00"))

        assertEquals(LimitRuleChangeStatus.REQUESTED, result.status)
        val evaluations = repositories.limitChangePolicyEvaluations
            .findByChangeRequest(tenantId, LimitChangeRequestId.of(result.id))
        assertEquals(listOf(reviewPolicyId), evaluations.map { it.policyId })
        assertEquals(mapOf("conditionResult" to true), evaluations.single().actionResult.value)
    }

    @Test
    fun `non-matching JSON Logic policy continues to the next policy`() {
        val repositories = repositoriesWithConfiguration(LimitChangePolicyDecision.ALLOW)
        repositories.limitChangePolicies.insert(
            tenantId,
            authorizationPolicy(
                decision = LimitChangePolicyDecision.REQUIRE_APPROVAL,
                id = reviewPolicyId,
                code = "REVIEW_LIMIT_CHANGE",
                priority = 10,
                conditionExpression = amountGreaterThan("600.00"),
            ),
        )
        val service = limitChangeService(repositories)

        val result = service.requestLimitRuleChange(command(amount = "500.00"))

        assertEquals(LimitRuleChangeStatus.APPLIED, result.status)
        val evaluations = repositories.limitChangePolicyEvaluations
            .findByChangeRequest(tenantId, LimitChangeRequestId.of(result.id))
        assertEquals(listOf(reviewPolicyId, authorizationPolicyId), evaluations.map { it.policyId })
        assertEquals(
            listOf(
                LimitChangePolicyEvaluationOutcome.NOT_MATCHED,
                LimitChangePolicyEvaluationOutcome.APPLIED,
            ),
            evaluations.map { it.outcome },
        )
    }

    @Test
    fun `exact decimal policy comparison preserves precision`() {
        val repositories = repositoriesWithConfiguration(LimitChangePolicyDecision.ALLOW)
        repositories.limitChangePolicies.insert(
            tenantId,
            authorizationPolicy(
                decision = LimitChangePolicyDecision.REQUIRE_APPROVAL,
                id = reviewPolicyId,
                code = "REVIEW_PRECISE_EXPOSURE",
                priority = 10,
                conditionExpression = mapOf(
                    "decimal_gt" to listOf(
                        mapOf("var" to "facts.requestedExposure"),
                        "9999999999999999.0000",
                    )
                ),
            ),
        )
        val service = limitChangeService(repositories)

        val result = service.requestLimitRuleChange(
            command().copy(
                policyContext = mapOf("requestedExposure" to "9999999999999999.0001")
            )
        )

        assertEquals(LimitRuleChangeStatus.REQUESTED, result.status)
        val evaluation = repositories.limitChangePolicyEvaluations
            .findByChangeRequest(tenantId, LimitChangeRequestId.of(result.id))
            .single()
        assertEquals(reviewPolicyId, evaluation.policyId)
        assertEquals(mapOf("conditionResult" to true), evaluation.actionResult.value)
    }

    @Test
    fun `policy action caps the requested amount before automatic approval`() {
        val repositories = repositoriesWithConfiguration(LimitChangePolicyDecision.ALLOW)
        repositories.limitChangePolicies.insert(
            tenantId,
            authorizationPolicy(
                decision = LimitChangePolicyDecision.NO_OP,
                id = reviewPolicyId,
                code = "CAP_REQUESTED_AMOUNT",
                priority = 10,
                actionExpression = listOf(
                    mapOf(
                        "type" to "CAP_REQUESTED_AMOUNT",
                        "amount" to "600.00",
                    )
                ),
            ),
        )
        val service = limitChangeService(repositories)
        val requestCommand = command(idempotencyKey = "policy-cap", amount = "700.00")

        val result = service.requestLimitRuleChange(requestCommand)
        val replayed = service.requestLimitRuleChange(requestCommand)

        assertEquals(result, replayed)
        assertFailsWith<IdempotencyConflictException> {
            service.requestLimitRuleChange(command(idempotencyKey = "policy-cap", amount = "600.00"))
        }
        assertEquals(LimitRuleChangeStatus.APPLIED, result.status)
        val rule = assertNotNull(
            repositories.limitRules.findById(tenantId, LimitRuleId.of(assertNotNull(result.limitRuleId)))
        )
        assertEquals(BigDecimal("600.0000"), rule.amount.amount)
        val request = assertNotNull(
            repositories.limitChangeRequests.findById(tenantId, LimitChangeRequestId.of(result.id))
        )
        assertEquals("600.0000", request.requestedRule.value["amount"])
        assertEquals("600.0000", request.approvedRule?.value?.get("amount"))
        val capEvaluation = repositories.limitChangePolicyEvaluations
            .findByChangeRequest(tenantId, request.id)
            .first()
        assertEquals(LimitChangePolicyEvaluationOutcome.APPLIED, capEvaluation.outcome)
        assertEquals(
            listOf(
                mapOf(
                    "type" to "CAP_REQUESTED_AMOUNT",
                    "maximum" to "600.0000",
                    "currency" to "EUR",
                    "before" to "700.0000",
                    "after" to "600.0000",
                    "changed" to true,
                )
            ),
            capEvaluation.actionResult.value["actions"],
        )
        val capEvent = repositories.limitRuleChangeEvents
            .findByChangeRequest(tenantId, request.id)
            .single {
                it.eventType == LimitRuleChangeEventType.POLICY_EVALUATED &&
                    it.policyId == reviewPolicyId
            }
        assertEquals("700.0000", capEvent.beforeRule?.value?.get("amount"))
        assertEquals("600.0000", capEvent.afterRule?.value?.get("amount"))
    }

    @Test
    fun `policy action schedules an automatically approved request`() {
        val repositories = repositoriesWithConfiguration(LimitChangePolicyDecision.ALLOW)
        val scheduledAt = now.plusSeconds(3600)
        repositories.limitChangePolicies.insert(
            tenantId,
            authorizationPolicy(
                decision = LimitChangePolicyDecision.ALLOW,
                id = reviewPolicyId,
                code = "DELAY_LIMIT_INCREASE",
                priority = 10,
                actionExpression = listOf(
                    mapOf(
                        "type" to "SET_EFFECTIVE_AT",
                        "instant" to mapOf("var" to "facts.scheduleAt"),
                    )
                ),
            ),
        )
        val service = limitChangeService(repositories)
        val requestCommand = command(idempotencyKey = "policy-schedule").copy(
            policyContext = mapOf("scheduleAt" to scheduledAt.toString())
        )

        val result = service.requestLimitRuleChange(requestCommand)
        val replayed = service.requestLimitRuleChange(requestCommand)

        assertEquals(result, replayed)
        assertFailsWith<IdempotencyConflictException> {
            service.requestLimitRuleChange(
                command(idempotencyKey = "policy-schedule").copy(
                    policyContext = requestCommand.policyContext,
                    effectiveAt = scheduledAt,
                )
            )
        }
        assertEquals(LimitRuleChangeStatus.PENDING_EFFECTIVE_DATE, result.status)
        assertEquals(scheduledAt, result.effectiveAt)
        assertEquals(null, result.limitRuleId)
        val evaluation = repositories.limitChangePolicyEvaluations
            .findByChangeRequest(tenantId, LimitChangeRequestId.of(result.id))
            .single()
        assertEquals(
            listOf(
                mapOf(
                    "type" to "SET_EFFECTIVE_AT",
                    "before" to null,
                    "after" to scheduledAt.toString(),
                    "changed" to true,
                )
            ),
            evaluation.actionResult.value["actions"],
        )
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
    fun `invalid JSON Logic expression fails closed with an auditable request`() {
        val repositories = repositoriesWithConfiguration(LimitChangePolicyDecision.ALLOW)
        repositories.limitChangePolicies.insert(
            tenantId,
            authorizationPolicy(
                decision = LimitChangePolicyDecision.REQUIRE_APPROVAL,
                id = reviewPolicyId,
                code = "INVALID_LIMIT_CHANGE_POLICY",
                priority = 10,
                conditionExpression = mapOf("unsupported_operation" to emptyList<Any?>()),
            ),
        )
        val service = limitChangeService(repositories)

        val result = service.requestLimitRuleChange(command())

        assertEquals(LimitRuleChangeStatus.FAILED, result.status)
        assertEquals(null, result.limitRuleId)
        val evaluation = repositories.limitChangePolicyEvaluations
            .findByChangeRequest(tenantId, LimitChangeRequestId.of(result.id))
            .single()
        assertEquals(reviewPolicyId, evaluation.policyId)
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
    fun `invalid policy action fails closed before it can approve a request`() {
        val repositories = repositoriesWithConfiguration(LimitChangePolicyDecision.ALLOW)
        repositories.limitChangePolicies.insert(
            tenantId,
            authorizationPolicy(
                decision = LimitChangePolicyDecision.ALLOW,
                id = reviewPolicyId,
                code = "UNSUPPORTED_POLICY_ACTION",
                priority = 10,
                actionExpression = listOf(mapOf("type" to "CANCEL_PENDING")),
            ),
        )
        val service = limitChangeService(repositories)

        val result = service.requestLimitRuleChange(command())

        assertEquals(LimitRuleChangeStatus.FAILED, result.status)
        val evaluation = repositories.limitChangePolicyEvaluations
            .findByChangeRequest(tenantId, LimitChangeRequestId.of(result.id))
            .single()
        assertEquals(LimitChangePolicyEvaluationOutcome.ERROR, evaluation.outcome)
        assertEquals(false, evaluation.matched)
    }

    @Test
    fun `inexact monetary comparison is rejected by policy validation`() {
        val repositories = repositoriesWithConfiguration(LimitChangePolicyDecision.ALLOW)
        repositories.limitChangePolicies.insert(
            tenantId,
            authorizationPolicy(
                decision = LimitChangePolicyDecision.ALLOW,
                id = reviewPolicyId,
                code = "INEXACT_AMOUNT_POLICY",
                priority = 10,
                conditionExpression = mapOf(
                    ">" to listOf(
                        mapOf("var" to "changeRequest.requestedRule.amount"),
                        600,
                    )
                ),
            ),
        )
        val service = limitChangeService(repositories)

        val result = service.requestLimitRuleChange(command(amount = "700.00"))

        assertEquals(LimitRuleChangeStatus.FAILED, result.status)
        val evaluation = repositories.limitChangePolicyEvaluations
            .findByChangeRequest(tenantId, LimitChangeRequestId.of(result.id))
            .single()
        assertEquals(LimitChangePolicyEvaluationOutcome.ERROR, evaluation.outcome)
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

        val firstCommand = command(idempotencyKey = "request-1").copy(
            policyContext = mapOf(
                "attempt" to 1L,
                "nested" to mapOf("values" to listOf(1L, BigDecimal("0.12345678901234567890"))),
            ),
            metadata = mapOf("attempt" to 2L),
            requestedRule = requireNotNull(command().requestedRule).copy(
                metadata = mapOf("attempt" to 3L),
            ),
        )
        val first = service.requestLimitRuleChange(firstCommand)
        clock.advanceBy(Duration.ofMinutes(5))
        val second = service.requestLimitRuleChange(
            firstCommand.copy(
                policyContext = mapOf(
                    "attempt" to 1,
                    "nested" to mapOf("values" to listOf(1, BigDecimal("0.12345678901234567890"))),
                ),
                metadata = mapOf("attempt" to 2),
                requestedRule = requireNotNull(firstCommand.requestedRule).copy(
                    metadata = mapOf("attempt" to 3),
                ),
            ),
        )

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
        assertFailsWith<IdempotencyConflictException> {
            service.requestLimitRuleChange(
                firstCommand.copy(
                    policyContext = mapOf(
                        "attempt" to 2,
                        "nested" to mapOf("values" to listOf(1, BigDecimal("0.12345678901234567890"))),
                    ),
                ),
            )
        }
    }

    @Test
    fun `applied request replays after its expiry`() {
        val repositories = repositoriesWithConfiguration()
        val clock = FixedClockProvider(now)
        val service = limitChangeService(repositories, clock)
        val request = command(idempotencyKey = "expired-replay").copy(
            expiresAt = now.plus(Duration.ofMinutes(1)),
        )

        val first = service.requestLimitRuleChange(request)
        clock.advanceBy(Duration.ofMinutes(2))
        val replayed = service.requestLimitRuleChange(request)

        assertEquals(LimitRuleChangeStatus.APPLIED, first.status)
        assertEquals(first, replayed)
        assertEquals(
            4,
            repositories.limitRuleChangeEvents.findByChangeRequest(
                tenantId,
                LimitChangeRequestId.of(first.id),
            ).size,
        )
    }

    @Test
    fun `new request with an already expired deadline is rejected`() {
        val repositories = repositoriesWithConfiguration()
        val service = limitChangeService(repositories)
        val request = command(idempotencyKey = "already-expired").copy(
            expiresAt = now.minusSeconds(1),
        )

        assertFailsWith<ValidationException> {
            service.requestLimitRuleChange(request)
        }
        assertNull(
            repositories.limitChangeRequests.findByIdempotencyKey(
                tenantId,
                IdempotencyKey.of("already-expired"),
            ),
        )
    }

    @Test
    fun `altered request with the same expired idempotency key conflicts`() {
        val repositories = repositoriesWithConfiguration()
        val clock = FixedClockProvider(now)
        val service = limitChangeService(repositories, clock)
        val original = command(idempotencyKey = "expired-conflict", amount = "500.00").copy(
            expiresAt = now.plus(Duration.ofMinutes(1)),
        )

        service.requestLimitRuleChange(original)
        clock.advanceBy(Duration.ofMinutes(2))

        assertFailsWith<IdempotencyConflictException> {
            service.requestLimitRuleChange(
                command(idempotencyKey = "expired-conflict", amount = "600.00").copy(
                    expiresAt = original.expiresAt,
                ),
            )
        }
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
    fun `future rules reject reverse insertion overlap with open ends`() {
        val repositories = repositoriesWithConfiguration()
        val service = limitChangeService(repositories)
        val laterStart = now.plusSeconds(120)

        val laterRule = service.requestLimitRuleChange(
            command(name = "Later rule", startsAt = laterStart),
        )

        assertEquals(LimitRuleChangeStatus.APPLIED, laterRule.status)
        assertFailsWith<ConflictException> {
            service.requestLimitRuleChange(
                command(name = "Earlier rule", startsAt = now.plusSeconds(60)),
            )
        }
        assertEquals(
            listOf(laterRule.limitRuleId),
            repositories.limitRules.findByOwner(tenantId, PartyProfileId.of(ownerId)).map { it.id.value },
        )
    }

    @Test
    fun `touching future rule intervals do not conflict`() {
        val repositories = repositoriesWithConfiguration()
        val service = limitChangeService(repositories)
        val boundary = now.plusSeconds(120)

        val first = service.requestLimitRuleChange(
            command(
                name = "First interval",
                startsAt = now.plusSeconds(60),
                endsAt = boundary,
            ),
        )
        val second = service.requestLimitRuleChange(
            command(
                name = "Second interval",
                startsAt = boundary,
                endsAt = now.plusSeconds(180),
            ),
        )

        assertEquals(LimitRuleChangeStatus.APPLIED, first.status)
        assertEquals(LimitRuleChangeStatus.APPLIED, second.status)
        assertEquals(2, repositories.limitRules.findByOwner(tenantId, PartyProfileId.of(ownerId)).size)
    }

    @Test
    fun `approval rejects a future interval that starts overlapping after request`() {
        val repositories = repositoriesWithConfiguration(LimitChangePolicyDecision.REQUIRE_APPROVAL)
        val service = limitChangeService(repositories)
        val startsAt = now.plusSeconds(120)
        val request = service.requestLimitRuleChange(
            command(name = "Pending future rule", startsAt = startsAt),
        )
        assertEquals(LimitRuleChangeStatus.REQUESTED, request.status)

        val existingRule = insertRule(repositories)
        repositories.limitRules.update(
            tenantId,
            existingRule.copy(
                name = LimitRuleName.of("Existing future rule"),
                effectiveWindow = EffectiveWindow(now.plusSeconds(60), null),
            ),
        )

        assertFailsWith<ConflictException> {
            service.approveLimitRuleChange(
                ApproveLimitRuleChangeCommand(
                    tenantId = tenantId.value,
                    changeRequestId = request.id,
                    approvedBy = LimitChangeActorCommand(LimitChangeActorType.SYSTEM),
                ),
            )
        }
        val persistedRequest = assertNotNull(
            repositories.limitChangeRequests.findById(tenantId, LimitChangeRequestId.of(request.id)),
        )
        assertEquals(
            listOf(request.id),
            repositories.limitChangeRequests
                .findPendingForTarget(tenantId, persistedRequest.targetScopeHash, now)
                .map { it.id.value },
        )
    }

    @Test
    fun `due application rejects overlap introduced after scheduling`() {
        val repositories = repositoriesWithConfiguration()
        val service = limitChangeService(repositories)
        val dueAt = now.plusSeconds(3600)
        val scheduled = service.requestLimitRuleChange(
            command(
                name = "Scheduled future rule",
                startsAt = dueAt,
                effectiveAt = dueAt,
            ),
        )
        assertEquals(LimitRuleChangeStatus.PENDING_EFFECTIVE_DATE, scheduled.status)

        val existingRule = insertRule(repositories)
        repositories.limitRules.update(
            tenantId,
            existingRule.copy(
                name = LimitRuleName.of("Existing due overlap"),
                effectiveWindow = EffectiveWindow(now.plusSeconds(60), null),
            ),
        )

        val result = service.applyDueLimitChanges(ApplyDueLimitChangesCommand(tenantId.value, dueAt))

        assertEquals(0, result.appliedCount)
        assertEquals(1, result.failedCount)
        assertEquals(listOf(scheduled.id), result.failedChangeRequestIds)
        assertEquals(
            DomainLimitRuleChangeStatus.FAILED,
            repositories.limitChangeRequests.findById(tenantId, LimitChangeRequestId.of(scheduled.id))?.status,
        )
        assertEquals(1, repositories.limitRules.findByOwner(tenantId, PartyProfileId.of(ownerId)).size)
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
    fun `applyDueLimitChanges revalidates the configuration before creating the rule`() {
        val repositories = repositoriesWithConfiguration()
        val service = limitChangeService(repositories)
        val dueAt = now.plusSeconds(3600)
        val scheduled = service.requestLimitRuleChange(
            command().copy(effectiveAt = dueAt),
        )
        val configuration = assertNotNull(
            repositories.limitConfigurations.findById(tenantId, configurationId),
        )
        repositories.limitConfigurations.update(
            tenantId,
            configuration.copy(isActive = false),
        )

        val result = service.applyDueLimitChanges(
            ApplyDueLimitChangesCommand(tenantId.value, dueAt),
        )

        assertEquals(0, result.appliedCount)
        assertEquals(1, result.failedCount)
        assertEquals(listOf(scheduled.id), result.failedChangeRequestIds)
        val failedRequest = assertNotNull(
            repositories.limitChangeRequests.findById(tenantId, LimitChangeRequestId.of(scheduled.id)),
        )
        assertEquals(DomainLimitRuleChangeStatus.FAILED, failedRequest.status)
        assertEquals("configuration must be active", failedRequest.decisionReason)
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
        conditionExpression: Map<String, Any?> = emptyMap(),
        actionExpression: List<Map<String, Any?>> = emptyList(),
        limitConfigurationId: LimitConfigurationId? = null,
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
            limitConfigurationId = limitConfigurationId,
            priority = LimitPriority.of(priority),
            conditionExpression = Metadata(conditionExpression),
            actionExpression = actionExpression,
            denialCode = if (decision == LimitChangePolicyDecision.DENY) "LIMIT_CHANGE_DENIED" else null,
            denialMessage = if (decision == LimitChangePolicyDecision.DENY) {
                "Change denied by test policy"
            } else {
                null
            },
            createdAt = now,
            updatedAt = now,
        )

    private fun insertConfiguration(
        repositories: InMemoryLimitguardRepositories,
        id: LimitConfigurationId,
        parentConfigurationId: LimitConfigurationId? = null,
        maxAssignableAmount: String = "1000.00",
        fundingScope: LimitConfigurationFundingScope = LimitConfigurationFundingScope.AllFundingSources,
    ): LimitConfiguration = repositories.limitConfigurations.insert(
        tenantId,
        LimitConfiguration(
            id = id,
            tenantId = tenantId,
            parentConfigurationId = parentConfigurationId,
            code = LimitConfigurationCode.of("CONFIG_${id.value.toString().takeLast(4)}"),
            name = LimitConfigurationName.of("Test configuration ${id.value.toString().takeLast(4)}"),
            description = null,
            limitKind = DomainLimitKind.PerTransaction,
            fundingScope = fundingScope,
            channelScope = DomainLimitChannelScope.AllChannels,
            operationScope = LimitOperationScope.AllOperations,
            maxAssignableAmount = Money.positive(maxAssignableAmount, CurrencyCode.of("EUR")),
            defaultAmount = null,
            priority = LimitPriority.of(100),
            isActive = true,
            effectiveWindow = EffectiveWindow(now.minusSeconds(60), null),
            metadata = Metadata.EMPTY,
            createdAt = now,
        )
    )

    private fun amountGreaterThan(amount: String): Map<String, Any?> =
        mapOf(
            "decimal_gt" to listOf(
                mapOf("var" to "changeRequest.requestedRule.amount"),
                amount,
            )
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
            targetScope = candidate.toTargetScope(requireNotNull(facts.configuration).fundingScope),
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
        policyEngines: Collection<PolicyEngine> = listOf(JsonLogicPolicyEngine()),
    ): DefaultLimitChangeService {
        return DefaultLimitChangeService(
            changeRequestRepository = repositories.limitChangeRequests,
            changeEventRepository = repositories.limitRuleChangeEvents,
            limitRuleRepository = repositories.limitRules,
            limitRuleRevisionRepository = repositories.limitRuleRevisions,
            limitConfigurationRepository = repositories.limitConfigurations,
            mandateRepository = repositories.mandates,
            policyRepository = repositories.limitChangePolicies,
            policyEvaluationRepository = repositories.limitChangePolicyEvaluations,
            policyEngines = policyEngines,
            idGenerator = SequentialIdGenerator(startAt = 100L),
            clockProvider = clock,
            transactionRunner = DirectTransactionRunner(),
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
        metadata: Map<String, Any?> = emptyMap(),
        startsAt: Instant? = null,
        endsAt: Instant? = null,
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
            startsAt = startsAt,
            endsAt = endsAt,
            metadata = metadata,
        )

    private fun command(
        idempotencyKey: String? = null,
        name: String = "Daily ATM limit",
        amount: String = "500.00",
        expiresAt: Instant? = null,
        policyContext: Map<String, Any?> = emptyMap(),
        metadata: Map<String, Any?> = emptyMap(),
        startsAt: Instant? = null,
        endsAt: Instant? = null,
        effectiveAt: Instant? = null,
    ): RequestLimitRuleChangeCommand =
        RequestLimitRuleChangeCommand(
            tenantId = tenantId.value,
            changeType = LimitRuleChangeType.CREATE,
            requestedBy = LimitChangeActorCommand(
                actorType = LimitChangeActorType.PARTY_PROFILE,
                partyProfileId = requesterId,
            ),
            targetOwnerPartyProfileId = ownerId,
            requestedRule = draft(
                name = name,
                amount = amount,
                startsAt = startsAt,
                endsAt = endsAt,
            ),
            requestChannelId = channelId,
            effectiveAt = effectiveAt,
            expiresAt = expiresAt,
            policyContext = policyContext,
            idempotencyKey = idempotencyKey,
            metadata = metadata,
        )

}
