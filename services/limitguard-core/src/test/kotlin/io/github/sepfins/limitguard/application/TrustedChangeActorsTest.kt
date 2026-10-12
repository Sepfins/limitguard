package io.github.sepfins.limitguard.application

import io.github.sepfins.limitguard.api.LimitChangeActorType
import io.github.sepfins.limitguard.api.LimitChannelScope
import io.github.sepfins.limitguard.api.LimitMandateScope
import io.github.sepfins.limitguard.api.LimitRuleChangeStatus
import io.github.sepfins.limitguard.api.LimitRuleChangeType
import io.github.sepfins.limitguard.api.TrustedChangeActorContext
import io.github.sepfins.limitguard.api.commands.ApplyDueLimitChangesCommand
import io.github.sepfins.limitguard.api.commands.ApproveLimitRuleChangeCommand
import io.github.sepfins.limitguard.api.commands.CancelLimitRuleChangeCommand
import io.github.sepfins.limitguard.api.commands.CreatePartyProfileDelegationCommand
import io.github.sepfins.limitguard.api.commands.LimitChangeActorCommand
import io.github.sepfins.limitguard.api.commands.RejectLimitRuleChangeCommand
import io.github.sepfins.limitguard.api.commands.SetChannelActiveCommand
import io.github.sepfins.limitguard.api.commands.SetLimitConfigurationActiveCommand
import io.github.sepfins.limitguard.api.PartyProfileDelegationType
import io.github.sepfins.limitguard.api.errors.ConflictException
import io.github.sepfins.limitguard.api.errors.NotFoundException
import io.github.sepfins.limitguard.api.errors.ValidationException
import io.github.sepfins.limitguard.api.queries.GetLimitRuleQuery
import io.github.sepfins.limitguard.domain.change.LimitChangePolicy
import io.github.sepfins.limitguard.domain.change.LimitChangePolicyEngine
import io.github.sepfins.limitguard.domain.change.LimitChangePolicyEvaluationOutcome
import io.github.sepfins.limitguard.domain.change.LimitChangePolicyPhase
import io.github.sepfins.limitguard.domain.common.ChannelId
import io.github.sepfins.limitguard.domain.common.ChannelGroupId
import io.github.sepfins.limitguard.domain.common.OperationTypeId
import io.github.sepfins.limitguard.domain.common.EffectiveWindow
import io.github.sepfins.limitguard.domain.common.IdempotencyKey
import io.github.sepfins.limitguard.domain.common.LimitConfigurationId
import io.github.sepfins.limitguard.domain.common.MandateId
import io.github.sepfins.limitguard.domain.common.PartyProfileDelegationId
import io.github.sepfins.limitguard.domain.common.TenantId
import io.github.sepfins.limitguard.domain.limit.LimitChannelScope as DomainChannelScope
import io.github.sepfins.limitguard.domain.limit.LimitOperationScope as DomainOperationScope
import io.github.sepfins.limitguard.domain.policy.PolicyEvaluationContext
import io.github.sepfins.limitguard.domain.policy.PolicyEvaluationResult
import io.github.sepfins.limitguard.port.persistence.LimitChangePolicyRepository
import io.github.sepfins.limitguard.port.policy.PolicyEngine
import io.github.sepfins.limitguard.support.FixedClockProvider
import io.github.sepfins.limitguard.support.TrustedChangeScenario
import io.github.sepfins.limitguard.support.createFacade
import io.github.sepfins.limitguard.support.persistence.InMemoryLimitguardRepositories
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TrustedChangeActorsTest {
    @Test
    fun `self service representatives and internal SYSTEM retain distinct identities`() {
        for (path in listOf("OWNER", "REPRESENTATIVE", "PARTY_SUBMITTER", "SYSTEM", "SYSTEM_REPRESENTATIVE")) {
            val fixture = Fixture()
            val s = fixture.scenario
            val command = when (path) {
                "OWNER" -> s.command
                "REPRESENTATIVE" -> s.represented()
                "PARTY_SUBMITTER" -> s.command.copy(requestedBy = s.party(s.unrelated), requesterPartyProfileId = s.representative, authorityDelegationId = s.delegation)
                "SYSTEM" -> s.command.copy(requestedBy = SYSTEM, requestChannelId = null)
                else -> s.command.copy(requestedBy = SYSTEM, requesterPartyProfileId = s.representative, authorityDelegationId = s.delegation)
            }
            val context = when (path) {
                "SYSTEM", "SYSTEM_REPRESENTATIVE" -> TrustedChangeActorContext.InternalSystem
                else -> null
            }
            val result = s.facade.limitChangeService.requestLimitRuleChange(command, context)
            assertEquals(LimitRuleChangeStatus.REQUESTED, result.status)
            assertEquals(command.requestedBy.actorType, result.requestedBy.actorType)
            assertEquals(command.requestedBy.partyProfileId, result.requestedBy.partyProfileId)
            assertEquals(command.requesterPartyProfileId ?: command.requestedBy.partyProfileId, result.requesterPartyProfileId)
            assertEquals(s.owner, result.targetOwnerPartyProfileId)
            assertEquals(command.requestChannelId, result.requestChannelId)
            assertEquals(command.authorityDelegationId, result.authorityDelegationId)
            assertNull(result.targetMandateId)
            assertEquals(result, s.details(result.id).request)
            assertEquals(1, fixture.evaluations)
        }
    }

    @Test
    fun `SYSTEM authority cannot come from the actor enum and party actors cannot assert it`() {
        val fixture = Fixture()
        val s = fixture.scenario
        val pending = s.facade.limitChangeService.requestLimitRuleChange(s.command)
        fixture.resetPolicies()
        assertFailsWith<ValidationException> {
            s.facade.limitChangeService.requestLimitRuleChange(s.command.copy(requestedBy = SYSTEM, requesterPartyProfileId = s.owner))
        }
        assertFailsWith<ValidationException> {
            s.facade.limitChangeService.approveLimitRuleChange(ApproveLimitRuleChangeCommand(s.tenant, pending.id, SYSTEM))
        }
        assertFailsWith<ValidationException> {
            s.facade.limitChangeService.rejectLimitRuleChange(RejectLimitRuleChangeCommand(s.tenant, pending.id, SYSTEM, "Declined"))
        }
        assertFailsWith<ValidationException> {
            s.facade.limitChangeService.cancelLimitRuleChange(CancelLimitRuleChangeCommand(s.tenant, pending.id, SYSTEM))
        }
        assertFailsWith<ValidationException> {
            s.facade.limitChangeService.requestLimitRuleChange(s.command, TrustedChangeActorContext.InternalSystem)
        }
        assertEquals(LimitRuleChangeStatus.REQUESTED, s.details(pending.id).request.status)
        fixture.assertNoPolicies()
    }

    @Test
    fun `missing and other tenant references are typed errors before admission or policies`() {
        val fixture = Fixture()
        val s = fixture.scenario
        val other = TrustedChangeScenario(s.facade, NOW)
        val otherPending = s.facade.limitChangeService.requestLimitRuleChange(other.command)
        val otherApplied = s.facade.limitChangeService.approveLimitRuleChange(
            ApproveLimitRuleChangeCommand(other.tenant, otherPending.id, SYSTEM), TrustedChangeActorContext.InternalSystem,
        )
        fixture.resetPolicies()
        val missing = UUID.randomUUID()
        val candidates = listOf(
            s.command.copy(tenantId = missing),
            s.command.copy(requestedBy = s.party(missing)),
            s.command.copy(requestedBy = s.party(other.owner)),
            s.command.copy(requesterPartyProfileId = missing),
            s.command.copy(requesterPartyProfileId = other.owner),
            s.command.copy(targetOwnerPartyProfileId = missing),
            s.command.copy(targetOwnerPartyProfileId = other.owner),
            s.command.copy(requestChannelId = missing),
            s.command.copy(requestChannelId = other.channel),
            s.command.copy(requestedRule = s.command.requestedRule!!.copy(configurationId = missing)),
            s.command.copy(requestedRule = s.command.requestedRule.copy(configurationId = other.configuration)),
            s.represented().copy(authorityDelegationId = missing),
            s.represented().copy(authorityDelegationId = other.delegation),
            s.command.copy(requestedRule = s.command.requestedRule.copy(mandateScope = LimitMandateScope.MANDATE, mandateId = missing)),
            s.command.copy(requestedRule = s.command.requestedRule.copy(mandateScope = LimitMandateScope.MANDATE, mandateId = other.mandate)),
            s.command.copy(changeType = LimitRuleChangeType.UPDATE, limitRuleId = missing, expectedVersion = 1),
            s.command.copy(changeType = LimitRuleChangeType.UPDATE, limitRuleId = otherApplied.limitRuleId, expectedVersion = 1),
        )
        for (command in candidates) {
            assertFailsWith<NotFoundException> { s.facade.limitChangeService.requestLimitRuleChange(command) }
            assertNull(fixture.repositories.limitChangeRequests.findByIdempotencyKey(
                TenantId.of(s.tenant), IdempotencyKey.of("submission"),
            ))
        }
        fixture.assertNoPolicies()
    }

    @Test
    fun `party submissions require an existing active Request Channel`() {
        val fixture = Fixture()
        val s = fixture.scenario
        assertFailsWith<ValidationException> { s.facade.limitChangeService.requestLimitRuleChange(s.command.copy(requestChannelId = null)) }
        s.facade.tenantCatalogService.setChannelActive(SetChannelActiveCommand(s.tenant, s.channel, false))
        assertFailsWith<ConflictException> { s.facade.limitChangeService.requestLimitRuleChange(s.command) }
        fixture.assertNoPolicies()
    }

    @Test
    fun `spending mandates and unrelated Delegations do not authorize intent`() {
        val fixture = Fixture()
        val s = fixture.scenario
        assertFailsWith<ValidationException> {
            s.facade.limitChangeService.requestLimitRuleChange(s.command.copy(requestedBy = s.party(s.representative)))
        }
        val unrelatedDelegation = s.facade.tenantCatalogService.createPartyProfileDelegation(CreatePartyProfileDelegationCommand(
            s.tenant, s.unrelated, s.representative, PartyProfileDelegationType.LEGAL_REPRESENTATIVE,
        )).id
        assertFailsWith<ConflictException> {
            s.facade.limitChangeService.requestLimitRuleChange(s.represented().copy(authorityDelegationId = unrelatedDelegation))
        }
        assertFailsWith<ValidationException> {
            s.facade.limitChangeService.requestLimitRuleChange(s.command.copy(authorityDelegationId = s.delegation))
        }
        fixture.assertNoPolicies()
    }

    @Test
    fun `representation uses the current half open Delegation window not a future application time`() {
        for (window in listOf(EffectiveWindow(NOW.minusSeconds(60), NOW), EffectiveWindow(NOW.plusSeconds(60), null))) {
            val fixture = Fixture()
            val s = fixture.scenario
            fixture.setDelegationWindow(window)
            assertFailsWith<ConflictException> {
                s.facade.limitChangeService.requestLimitRuleChange(s.represented().copy(effectiveAt = NOW.plusSeconds(120)))
            }
            fixture.assertNoPolicies()
        }
    }

    @Test
    fun `rule scope references are validated before business policies`() {
        val fixture = Fixture()
        val s = fixture.scenario
        val other = TrustedChangeScenario(s.facade, NOW)
        val tenant = TenantId.of(s.tenant)
        val configuration = assertNotNull(fixture.repositories.limitConfigurations.findById(tenant, LimitConfigurationId.of(s.configuration)))
        for (id in listOf(UUID.randomUUID(), other.ruleChannel)) {
            val drafts = listOf(
                s.command.requestedRule!!.copy(channelId = id),
                s.command.requestedRule.copy(channelScope = LimitChannelScope.CHANNEL_GROUP, channelId = null, channelGroupId = id),
                s.command.requestedRule.copy(operationTypeId = id),
            )
            for (draft in drafts) {
                val channelScope = if (draft.channelScope == LimitChannelScope.CHANNEL_GROUP) {
                    DomainChannelScope.ChannelGroupSpecific(ChannelGroupId.of(draft.channelGroupId!!))
                } else DomainChannelScope.ChannelSpecific(ChannelId.of(draft.channelId!!))
                val operationScope = draft.operationTypeId?.let { DomainOperationScope.OperationTypeSpecific(OperationTypeId.of(it)) }
                    ?: DomainOperationScope.AllOperations
                fixture.repositories.limitConfigurations.update(tenant, configuration.copy(channelScope = channelScope, operationScope = operationScope))
                assertFailsWith<NotFoundException> {
                    s.facade.limitChangeService.requestLimitRuleChange(s.command.copy(requestedRule = draft))
                }
            }
        }
        fixture.assertNoPolicies()
    }

    @Test
    fun `approval validates today's action actor and representation but not the original Request Channel`() {
        val fixture = Fixture()
        val s = fixture.scenario
        val pending = s.facade.limitChangeService.requestLimitRuleChange(s.represented())
        s.facade.tenantCatalogService.setChannelActive(SetChannelActiveCommand(s.tenant, s.channel, false))
        val before = s.details(pending.id)
        assertFailsWith<NotFoundException> {
            s.facade.limitChangeService.approveLimitRuleChange(ApproveLimitRuleChangeCommand(s.tenant, pending.id, s.party(UUID.randomUUID())))
        }
        assertEquals(before, s.details(pending.id))
        val applied = s.facade.limitChangeService.approveLimitRuleChange(
            ApproveLimitRuleChangeCommand(s.tenant, pending.id, SYSTEM), TrustedChangeActorContext.InternalSystem,
        )
        assertEquals(LimitRuleChangeStatus.APPLIED, applied.status)
        assertEquals(before.events.first(), s.details(pending.id).events.first())
        assertEquals(LimitChangeActorType.SYSTEM, s.details(pending.id).revisions.single().actor.actorType)
    }

    @Test
    fun `approval cannot bypass expired representation unavailable target Mandate or invalid adjusted references`() {
        val fixture = Fixture()
        val s = fixture.scenario
        val command = s.represented().copy(requestedRule = s.command.requestedRule!!.copy(mandateScope = LimitMandateScope.MANDATE, mandateId = s.mandate))
        val pending = s.facade.limitChangeService.requestLimitRuleChange(command)
        val before = s.details(pending.id)
        fixture.setDelegationWindow(EffectiveWindow(NOW.minusSeconds(60), NOW))
        assertFailsWith<ConflictException> { fixture.approve(pending.id) }
        fixture.setDelegationWindow(EffectiveWindow(NOW.minusSeconds(60), null))
        fixture.deactivateMandate()
        assertFailsWith<ConflictException> { fixture.approve(pending.id) }
        assertFailsWith<NotFoundException> {
            s.facade.limitChangeService.approveLimitRuleChange(ApproveLimitRuleChangeCommand(
                s.tenant, pending.id, SYSTEM, approvedRule = command.requestedRule!!.copy(mandateId = UUID.randomUUID()),
            ), TrustedChangeActorContext.InternalSystem)
        }
        assertEquals(before, s.details(pending.id))
    }

    @Test
    fun `scheduled SYSTEM application uses current representation`() {
        for (expired in listOf(false, true)) {
            val fixture = Fixture()
            val s = fixture.scenario
            val due = NOW.plusSeconds(60)
            val pending = s.facade.limitChangeService.requestLimitRuleChange(s.represented().copy(effectiveAt = due))
            fixture.approve(pending.id)
            if (expired) fixture.setDelegationWindow(EffectiveWindow(NOW.minusSeconds(60), due))
            fixture.clock.setTo(due)
            val result = s.facade.limitChangeService.applyDueLimitChanges(ApplyDueLimitChangesCommand(s.tenant), TrustedChangeActorContext.InternalSystem)
            assertEquals(if (expired) 0 else 1, result.appliedCount)
            assertEquals(if (expired) 1 else 0, result.failedCount)
            assertEquals(if (expired) 0 else 1, s.details(pending.id).revisions.size)
        }
    }

    @Test
    fun `replacement actors withdraw without original grant eligibility or any policy retrieval`() {
        for (cancel in listOf(false, true)) {
            val fixture = Fixture()
            val s = fixture.scenario
            val command = s.represented().copy(
                effectiveAt = if (cancel) NOW.plusSeconds(3600) else null,
                requestedRule = s.command.requestedRule!!.copy(mandateScope = LimitMandateScope.MANDATE, mandateId = s.mandate),
            )
            val pending = s.facade.limitChangeService.requestLimitRuleChange(command)
            if (cancel) fixture.approve(pending.id)
            val originalEvent = s.details(pending.id).events.first()
            fixture.deactivateMandate()
            fixture.setDelegationWindow(EffectiveWindow(NOW.minusSeconds(60), NOW.plusSeconds(60)))
            fixture.clock.setTo(NOW.plusSeconds(120))
            s.facade.tenantCatalogService.setChannelActive(SetChannelActiveCommand(s.tenant, s.channel, false))
            s.facade.tenantCatalogService.setLimitConfigurationActive(SetLimitConfigurationActiveCommand(s.tenant, s.configuration, false))
            fixture.resetPolicies(unavailable = true)
            val result = if (cancel) {
                s.facade.limitChangeService.cancelLimitRuleChange(CancelLimitRuleChangeCommand(s.tenant, pending.id, SYSTEM, "Withdrawn"), TrustedChangeActorContext.InternalSystem)
            } else {
                s.facade.limitChangeService.rejectLimitRuleChange(RejectLimitRuleChangeCommand(s.tenant, pending.id, s.party(s.unrelated), "Declined"))
            }
            assertEquals(if (cancel) LimitRuleChangeStatus.CANCELLED else LimitRuleChangeStatus.REJECTED, result.status)
            val details = s.details(pending.id)
            assertEquals(originalEvent, details.events.first())
            assertEquals(if (cancel) "Withdrawn" else "Declined", details.request.decisionReason)
            assertTrue(details.revisions.isEmpty())
            assertNull(result.limitRuleId)
            fixture.assertNoPolicies()
        }
    }

    @Test
    fun `withdrawing existing rule intent leaves the granted rule and revision history unchanged`() {
        for (cancel in listOf(false, true)) {
            val fixture = Fixture()
            val s = fixture.scenario
            val created = s.facade.limitChangeService.requestLimitRuleChange(s.command)
            val applied = fixture.approve(created.id)
            val ruleId = assertNotNull(applied.limitRuleId)
            val ruleBefore = s.facade.limitInquiryService.getLimitRule(GetLimitRuleQuery(s.tenant, ruleId))
            val historyBefore = s.details(created.id)
            val pending = s.facade.limitChangeService.requestLimitRuleChange(s.represented().copy(
                changeType = LimitRuleChangeType.UPDATE, limitRuleId = ruleId, expectedVersion = ruleBefore.version,
                requestedRule = s.command.requestedRule!!.copy(amount = BigDecimal("600")), idempotencyKey = "update",
            ))
            fixture.setDelegationWindow(EffectiveWindow(NOW.minusSeconds(60), NOW))
            fixture.resetPolicies(unavailable = true)
            if (cancel) {
                s.facade.limitChangeService.cancelLimitRuleChange(CancelLimitRuleChangeCommand(s.tenant, pending.id, s.party(s.unrelated)))
            } else {
                s.facade.limitChangeService.rejectLimitRuleChange(RejectLimitRuleChangeCommand(s.tenant, pending.id, s.party(s.unrelated), "Declined"))
            }
            assertEquals(ruleBefore, s.facade.limitInquiryService.getLimitRule(GetLimitRuleQuery(s.tenant, ruleId)))
            assertEquals(historyBefore, s.details(created.id))
            assertTrue(s.details(pending.id).revisions.isEmpty())
            fixture.assertNoPolicies()
        }
    }

    @Test
    fun `withdrawal validates action actors reason tenant and lifecycle without calling policies`() {
        val fixture = Fixture()
        val s = fixture.scenario
        val pending = s.facade.limitChangeService.requestLimitRuleChange(s.command)
        val other = TrustedChangeScenario(s.facade, NOW)
        fixture.resetPolicies(unavailable = true)
        for (id in listOf(UUID.randomUUID(), other.owner)) {
            assertFailsWith<NotFoundException> {
                s.facade.limitChangeService.rejectLimitRuleChange(RejectLimitRuleChangeCommand(s.tenant, pending.id, s.party(id), "Declined"))
            }
            assertFailsWith<NotFoundException> {
                s.facade.limitChangeService.cancelLimitRuleChange(CancelLimitRuleChangeCommand(s.tenant, pending.id, s.party(id)))
            }
        }
        assertFailsWith<NotFoundException> {
            s.facade.limitChangeService.cancelLimitRuleChange(CancelLimitRuleChangeCommand(other.tenant, pending.id, s.party(other.owner)))
        }
        assertFailsWith<ValidationException> {
            s.facade.limitChangeService.rejectLimitRuleChange(RejectLimitRuleChangeCommand(s.tenant, pending.id, s.party(s.owner), " "))
        }
        assertFailsWith<ValidationException> {
            s.facade.limitChangeService.cancelLimitRuleChange(CancelLimitRuleChangeCommand(s.tenant, pending.id, s.party(s.owner), " "))
        }
        val cancelled = s.facade.limitChangeService.cancelLimitRuleChange(CancelLimitRuleChangeCommand(s.tenant, pending.id, s.party(s.owner)))
        assertEquals(LimitRuleChangeStatus.CANCELLED, cancelled.status)
        assertFailsWith<ConflictException> {
            s.facade.limitChangeService.rejectLimitRuleChange(RejectLimitRuleChangeCommand(s.tenant, pending.id, s.party(s.owner), "Declined"))
        }
        assertFailsWith<ConflictException> {
            s.facade.limitChangeService.cancelLimitRuleChange(CancelLimitRuleChangeCommand(s.tenant, pending.id, s.party(s.owner)))
        }
        fixture.assertNoPolicies()
    }

    @Test
    fun `approval prevents rejection and application prevents cancellation`() {
        val fixture = Fixture()
        val s = fixture.scenario
        val pending = s.facade.limitChangeService.requestLimitRuleChange(s.command.copy(effectiveAt = NOW.plusSeconds(60)))
        fixture.approve(pending.id)
        assertFailsWith<ConflictException> {
            s.facade.limitChangeService.rejectLimitRuleChange(RejectLimitRuleChangeCommand(s.tenant, pending.id, s.party(s.owner), "Declined"))
        }
        fixture.clock.setTo(NOW.plusSeconds(60))
        s.facade.limitChangeService.applyDueLimitChanges(ApplyDueLimitChangesCommand(s.tenant), TrustedChangeActorContext.InternalSystem)
        val before = s.details(pending.id)
        assertFailsWith<ConflictException> {
            s.facade.limitChangeService.cancelLimitRuleChange(CancelLimitRuleChangeCommand(s.tenant, pending.id, s.party(s.owner)))
        }
        assertEquals(before, s.details(pending.id))
    }

    private class Fixture {
        val repositories = InMemoryLimitguardRepositories()
        val clock = FixedClockProvider(NOW)
        var policyReads = 0
        var evaluations = 0
        private var unavailable = false
        private val policies = object : LimitChangePolicyRepository by repositories.limitChangePolicies {
            override fun findActiveByPhase(tenantId: TenantId, phase: LimitChangePolicyPhase, at: Instant): List<LimitChangePolicy> {
                policyReads++
                check(!unavailable) { "policy infrastructure unavailable" }
                return repositories.limitChangePolicies.findActiveByPhase(tenantId, phase, at)
            }
        }
        private val engine = object : PolicyEngine {
            override val engine = LimitChangePolicyEngine.JSON_LOGIC
            override fun evaluate(policy: LimitChangePolicy, context: PolicyEvaluationContext): PolicyEvaluationResult {
                evaluations++
                check(!unavailable) { "policy facts unavailable" }
                return PolicyEvaluationResult(true, LimitChangePolicyEvaluationOutcome.MATCHED, policy.decision)
            }
        }
        val scenario = TrustedChangeScenario(repositories.createFacade(clock, listOf(engine), policies), NOW)

        fun approve(id: UUID) = scenario.facade.limitChangeService.approveLimitRuleChange(
            ApproveLimitRuleChangeCommand(scenario.tenant, id, SYSTEM), TrustedChangeActorContext.InternalSystem,
        )

        fun deactivateMandate() {
            val tenant = TenantId.of(scenario.tenant)
            val mandate = assertNotNull(repositories.mandates.findById(tenant, MandateId.of(scenario.mandate)))
            repositories.mandates.update(tenant, mandate.copy(isActive = false))
        }

        fun setDelegationWindow(window: EffectiveWindow) {
            val tenant = TenantId.of(scenario.tenant)
            val delegation = assertNotNull(repositories.partyProfileDelegations.findById(tenant, PartyProfileDelegationId.of(scenario.delegation)))
            repositories.partyProfileDelegations.update(tenant, delegation.copy(effectiveWindow = window))
        }

        fun resetPolicies(unavailable: Boolean = false) {
            policyReads = 0
            evaluations = 0
            this.unavailable = unavailable
        }

        fun assertNoPolicies() {
            assertEquals(0, policyReads)
            assertEquals(0, evaluations)
        }
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-10-04T12:00:00Z")
        val SYSTEM = LimitChangeActorCommand(LimitChangeActorType.SYSTEM)
    }
}
