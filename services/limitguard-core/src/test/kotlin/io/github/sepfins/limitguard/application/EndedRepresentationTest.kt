package io.github.sepfins.limitguard.application

import io.github.sepfins.limitguard.api.AuthorizationDecision
import io.github.sepfins.limitguard.api.LimitAmount
import io.github.sepfins.limitguard.api.LimitChangeActorType
import io.github.sepfins.limitguard.api.PartyProfileDelegationType
import io.github.sepfins.limitguard.api.TransactionStatus
import io.github.sepfins.limitguard.api.commands.CaptureTransactionCommand
import io.github.sepfins.limitguard.api.commands.CreateMandateCommand
import io.github.sepfins.limitguard.api.commands.CreatePartyProfileDelegationCommand
import io.github.sepfins.limitguard.api.commands.DeactivateMandateCommand
import io.github.sepfins.limitguard.api.commands.LimitChangeActorCommand
import io.github.sepfins.limitguard.api.commands.LimitRuleChangeOperation
import io.github.sepfins.limitguard.api.commands.RegisterTenantCommand
import io.github.sepfins.limitguard.api.commands.ReleaseAuthorizationCommand
import io.github.sepfins.limitguard.api.commands.ReverseTransactionCommand
import io.github.sepfins.limitguard.api.commands.TerminatePartyProfileDelegationCommand
import io.github.sepfins.limitguard.api.errors.ConflictException
import io.github.sepfins.limitguard.api.errors.NotFoundException
import io.github.sepfins.limitguard.support.FixedClockProvider
import io.github.sepfins.limitguard.support.OperationSubtreeScenario
import io.github.sepfins.limitguard.support.PostgresFacade
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

class EndedRepresentationTest {
    private val s = OperationSubtreeScenario(PostgresFacade.create(FixedClockProvider(NOW), emptyList()), NOW)
    private val catalog = s.facade.tenantCatalogService
    private val transfer = s.operation("TRANSFER", leaf = true)
    private val delegate = s.profile("Delegate")
    private val delegation = catalog.createPartyProfileDelegation(CreatePartyProfileDelegationCommand(
        s.tenant, s.owner, delegate, PartyProfileDelegationType.DELEGATE, startsAt = NOW.minusSeconds(60),
    )).id
    private val mandate = catalog.createMandate(CreateMandateCommand(
        s.tenant, s.owner, delegate, "Household", authorityDelegationId = delegation, startsAt = NOW,
    )).id

    @Test
    fun `a deactivated mandate denies new authorizations as if it never existed while earlier payments settle`() {
        assertEndedMandate(::deactivateMandate)
    }

    @Test
    fun `a mandate justified by a terminated delegation behaves the same`() {
        assertEndedMandate(::terminateDelegation)
    }

    @Test
    fun `change requests through ended representation are refused and direct ownership is unaffected`() {
        val configuration = s.configuration(transfer)
        val represented = s.changeCommand(configuration, setAmount).copy(
            requestedBy = LimitChangeActorCommand(LimitChangeActorType.PARTY_PROFILE, partyProfileId = delegate),
            authorityDelegationId = delegation,
        )
        val narrowed = s.changeCommand(s.configuration(transfer), setAmount, mandate = mandate)
        terminateDelegation()

        assertFailsWith<ConflictException> { s.facade.limitChangeService.requestLimitRuleChange(represented) }
        assertFailsWith<ConflictException> { s.facade.limitChangeService.requestLimitRuleChange(narrowed) }
        s.facade.limitChangeService.requestLimitRuleChange(s.changeCommand(configuration, setAmount))
    }

    @Test
    fun `ending keeps the record and is addressed by tenant and id alone`() {
        val ended = catalog.deactivateMandate(DeactivateMandateCommand(s.tenant, mandate))
        assertFalse(ended.isActive)
        assertEquals(ended, catalog.deactivateMandate(DeactivateMandateCommand(s.tenant, mandate)))
        assertFalse(catalog.terminatePartyProfileDelegation(TerminatePartyProfileDelegationCommand(s.tenant, delegation)).isActive)
        assertFailsWith<ConflictException> {
            s.request(s.configuration(transfer), setAmount, mandate = mandate)
        }

        val other = catalog.registerTenant(RegisterTenantCommand("Other bank ${UUID.randomUUID()}", ZoneOffset.UTC)).id
        assertFailsWith<NotFoundException> { catalog.deactivateMandate(DeactivateMandateCommand(other, mandate)) }
        assertFailsWith<NotFoundException> {
            catalog.terminatePartyProfileDelegation(TerminatePartyProfileDelegationCommand(other, delegation))
        }
        assertFailsWith<NotFoundException> { catalog.deactivateMandate(DeactivateMandateCommand(s.tenant, UUID.randomUUID())) }
    }

    private fun assertEndedMandate(end: () -> Unit) {
        val rule = s.grant(transfer, amount = "100")
        val payment = assertNotNull(s.authorize(transfer, "60", actor = delegate).paymentTransactionId)
        val stranger = s.authorize(transfer, actor = s.profile("Stranger"))

        end()

        val denied = s.authorize(transfer, actor = delegate)
        assertEquals(AuthorizationDecision.DENIED, denied.decision)
        assertEquals(stranger.violations, denied.violations)
        assertEquals(AuthorizationDecision.ALLOWED, s.authorize(transfer).decision)
        val payments = s.facade.limitEvaluationService
        payments.captureTransaction(CaptureTransactionCommand(s.tenant, payment, BigDecimal("30")))
        assertEquals(TransactionStatus.CAPTURED, payments.releaseAuthorization(ReleaseAuthorizationCommand(s.tenant, payment)).status)
        payments.reverseTransaction(ReverseTransactionCommand(s.tenant, payment, BigDecimal("10")))
        assertEquals(BigDecimal("0.0000"), s.usage(rule, delegate).reservedAmount)
        assertEquals(BigDecimal("20.0000"), s.usage(rule, delegate).consumedAmount)
    }

    private fun deactivateMandate() {
        catalog.deactivateMandate(DeactivateMandateCommand(s.tenant, mandate))
    }

    private fun terminateDelegation() {
        catalog.terminatePartyProfileDelegation(TerminatePartyProfileDelegationCommand(s.tenant, delegation))
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-10-24T12:00:00Z")
        val setAmount = LimitRuleChangeOperation.SetAmount(LimitAmount.Bounded(BigDecimal("500")))
    }
}
