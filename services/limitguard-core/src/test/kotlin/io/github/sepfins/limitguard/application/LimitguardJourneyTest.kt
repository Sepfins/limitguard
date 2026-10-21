package io.github.sepfins.limitguard.application

import io.github.sepfins.limitguard.api.AuthorizationDecision
import io.github.sepfins.limitguard.api.FundingSourceCategory
import io.github.sepfins.limitguard.api.LimitAmount
import io.github.sepfins.limitguard.api.LimitChangeActorType
import io.github.sepfins.limitguard.api.LimitKind
import io.github.sepfins.limitguard.api.LimitPeriod
import io.github.sepfins.limitguard.api.LimitRuleChangeStatus
import io.github.sepfins.limitguard.api.LimitRuleChangeType
import io.github.sepfins.limitguard.api.PartyType
import io.github.sepfins.limitguard.api.ProductNodeType
import io.github.sepfins.limitguard.api.TransactionStatus
import io.github.sepfins.limitguard.api.TrustedChangeActorContext
import io.github.sepfins.limitguard.api.commands.ApproveLimitRuleChangeCommand
import io.github.sepfins.limitguard.api.commands.AssignFundingSourceOwnerCommand
import io.github.sepfins.limitguard.api.commands.AuthorizeTransactionCommand
import io.github.sepfins.limitguard.api.commands.CaptureTransactionCommand
import io.github.sepfins.limitguard.api.commands.CreateLimitConfigurationCommand
import io.github.sepfins.limitguard.api.commands.LimitChangeActorCommand
import io.github.sepfins.limitguard.api.commands.LimitRuleChangeOperation
import io.github.sepfins.limitguard.api.commands.LimitRuleTargetCommand
import io.github.sepfins.limitguard.api.commands.RegisterChannelCommand
import io.github.sepfins.limitguard.api.commands.RegisterFundingSourceCommand
import io.github.sepfins.limitguard.api.commands.RegisterFundingSourceProductCommand
import io.github.sepfins.limitguard.api.commands.RegisterOperationTypeCommand
import io.github.sepfins.limitguard.api.commands.RegisterPartyProfileCommand
import io.github.sepfins.limitguard.api.commands.RegisterTenantCommand
import io.github.sepfins.limitguard.api.commands.RequestLimitRuleChangeCommand
import io.github.sepfins.limitguard.api.commands.ReverseTransactionCommand
import io.github.sepfins.limitguard.api.queries.GetCurrentUsageQuery
import io.github.sepfins.limitguard.api.queries.GetLimitRuleQuery
import io.github.sepfins.limitguard.support.FixedClockProvider
import io.github.sepfins.limitguard.support.PostgresFacade
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

// The whole library through its public facade over PostgreSQL, in the order a consuming backend uses it.
class LimitguardJourneyTest {
    private val limitguard = PostgresFacade.create(FixedClockProvider(Instant.parse("2026-10-24T09:00:00Z")))
    private val catalog = limitguard.tenantCatalogService
    private val changes = limitguard.limitChangeService
    private val payments = limitguard.limitEvaluationService
    private val inquiry = limitguard.limitInquiryService

    @Test
    fun `a customer gets a daily transfer limit approved, pays against it, and has it lowered`() {
        // Onboarding: a tenant, its customer, the customer's account, and the catalog a payment refers to.
        val tenant = catalog.registerTenant(RegisterTenantCommand("Journey Bank")).id
        val customer = catalog.registerPartyProfile(RegisterPartyProfileCommand(
            tenant, PartyType.INDIVIDUAL, firstName = "Maria", lastName = "Rossi",
        )).id
        val mobile = catalog.registerChannel(RegisterChannelCommand(tenant, "MOBILE", "Mobile banking")).id
        val transfer = catalog.registerOperationType(RegisterOperationTypeCommand(tenant, "TRANSFER", "Transfer", isLeaf = true)).id
        val currentAccount = catalog.registerFundingSourceProduct(RegisterFundingSourceProductCommand(
            tenant, "CURRENT_ACCOUNT", "Current account", FundingSourceCategory.TRANSACTIONAL, ProductNodeType.PRODUCT,
            isLeaf = true,
        )).id
        val account = catalog.registerFundingSource(RegisterFundingSourceCommand(tenant, currentAccount, "IT60X0542811101000000123456")).id
        catalog.assignFundingSourceOwner(AssignFundingSourceOwnerCommand(tenant, customer, account))

        // Configuration: the bank offers a daily transfer limit of up to 5,000 EUR.
        val dailyTransfers = catalog.createLimitConfiguration(CreateLimitConfigurationCommand(
            tenantId = tenant, code = "DAILY_TRANSFERS", name = "Daily transfers", limitKind = LimitKind.PERIODIC,
            period = LimitPeriod.DAILY, currency = "EUR", maxAssignableAmount = LimitAmount.Bounded(BigDecimal("5000")),
            operationTypeId = transfer,
        )).id

        fun authorize(amount: String) = payments.authorizeTransaction(
            AuthorizeTransactionCommand(tenant, customer, account, transfer, mobile, BigDecimal(amount), "EUR"),
        )
        fun usage(rule: UUID) = inquiry.getCurrentUsage(GetCurrentUsageQuery(tenant, rule, customer))
        fun requestAmount(amount: String, expectedVersion: Int? = null) = changes.requestLimitRuleChange(RequestLimitRuleChangeCommand(
            tenantId = tenant,
            requestedBy = LimitChangeActorCommand(LimitChangeActorType.PARTY_PROFILE, partyProfileId = customer),
            target = LimitRuleTargetCommand(customer, dailyTransfers),
            operation = LimitRuleChangeOperation.SetAmount(LimitAmount.Bounded(BigDecimal(amount))),
            requestChannelId = mobile,
            expectedVersion = expectedVersion,
        ))
        fun approve(request: UUID) = changes.approveLimitRuleChange(
            ApproveLimitRuleChangeCommand(tenant, request, LimitChangeActorCommand(LimitChangeActorType.SYSTEM)),
            TrustedChangeActorContext.InternalSystem,
        )

        // The tenant denies payments no rule covers, so nothing moves before a limit exists.
        assertEquals(AuthorizationDecision.DENIED, authorize("50").decision)

        // The customer asks for 1,000 EUR a day; the request waits for approval and grants nothing yet.
        val request = requestAmount("1000")
        assertEquals(LimitRuleChangeStatus.REQUESTED, request.status)
        assertNull(request.limitRuleId)
        assertEquals(AuthorizationDecision.DENIED, authorize("50").decision)

        val granted = approve(request.id)
        assertEquals(LimitRuleChangeStatus.APPLIED, granted.status)
        assertEquals(LimitRuleChangeType.CREATE, granted.changeType)
        val rule = requireNotNull(granted.limitRuleId)

        // Authorization reserves against the limit; a second payment that would exceed it is denied.
        val payment = authorize("300")
        assertEquals(AuthorizationDecision.ALLOWED, payment.decision)
        val paymentId = requireNotNull(payment.paymentTransactionId)
        assertEquals(BigDecimal("300.0000"), usage(rule).reservedAmount)
        assertEquals(AuthorizationDecision.DENIED, authorize("701").decision)
        assertEquals(BigDecimal("700.0000"), usage(rule).remainingAmount)

        // Capture turns the reservation into consumption.
        val capture = payments.captureTransaction(CaptureTransactionCommand(tenant, paymentId))
        assertEquals(TransactionStatus.CAPTURED, capture.status)
        assertEquals(BigDecimal("0.0000"), usage(rule).reservedAmount)
        assertEquals(BigDecimal("300.0000"), usage(rule).consumedAmount)

        // A reversal gives the amount back to the day's limit.
        val reversal = payments.reverseTransaction(ReverseTransactionCommand(tenant, paymentId, reason = "Refunded"))
        assertEquals(TransactionStatus.REVERSED, reversal.status)
        assertEquals(BigDecimal("0.0000"), usage(rule).consumedAmount)
        assertEquals(BigDecimal("1000.0000"), usage(rule).remainingAmount)

        // Lowering the limit is another approved request on the same target; it changes only the amount.
        val lowered = approve(requestAmount("400", expectedVersion = 1).id)
        assertEquals(LimitRuleChangeType.UPDATE, lowered.changeType)
        assertEquals(rule, lowered.limitRuleId)
        val current = inquiry.getLimitRule(GetLimitRuleQuery(tenant, rule))
        assertEquals(LimitAmount.Bounded(BigDecimal("400.0000")), current.amount)
        assertEquals(dailyTransfers, current.configurationId)
        assertEquals(2, current.version)
        assertEquals(AuthorizationDecision.DENIED, authorize("401").decision)
        assertEquals(AuthorizationDecision.ALLOWED, authorize("400").decision)
    }
}
