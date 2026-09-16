package io.github.sepfins.limitguard.demo.application

import io.github.sepfins.limitguard.api.AuthorizationDecision
import io.github.sepfins.limitguard.api.LimitKind
import io.github.sepfins.limitguard.api.OperationCapacityStatus
import io.github.sepfins.limitguard.api.TransactionEventType
import io.github.sepfins.limitguard.api.TransactionStatus
import io.github.sepfins.limitguard.api.Limitguard
import io.github.sepfins.limitguard.api.queries.GetLimitRuleQuery
import io.github.sepfins.limitguard.api.queries.GetOperationCapacityQuery
import io.github.sepfins.limitguard.api.queries.GetTransactionHistoryQuery
import io.github.sepfins.limitguard.api.results.OperationCapacityResult
import io.github.sepfins.limitguard.demo.domain.AccountType
import io.github.sepfins.limitguard.demo.domain.DemoAccount
import io.github.sepfins.limitguard.demo.domain.DemoCard
import io.github.sepfins.limitguard.demo.domain.DemoLimitSetting
import io.github.sepfins.limitguard.demo.domain.DemoOperation
import io.github.sepfins.limitguard.demo.domain.DemoPayment
import io.github.sepfins.limitguard.demo.domain.DemoPaymentTotals
import io.github.sepfins.limitguard.demo.persistence.DemoBankRepository
import io.github.sepfins.limitguard.demo.web.dto.AccountResponse
import io.github.sepfins.limitguard.demo.web.dto.CardResponse
import io.github.sepfins.limitguard.demo.web.dto.CustomerDto
import io.github.sepfins.limitguard.demo.web.dto.LimitResponse
import io.github.sepfins.limitguard.demo.web.dto.MoneyDto
import io.github.sepfins.limitguard.demo.web.dto.OverviewResponse
import io.github.sepfins.limitguard.demo.web.dto.PaymentDetailResponse
import io.github.sepfins.limitguard.demo.web.dto.PaymentSummaryResponse
import io.github.sepfins.limitguard.demo.web.dto.PaymentTimelineItemResponse
import io.github.sepfins.limitguard.demo.web.dto.PaymentViolationResponse
import io.github.sepfins.limitguard.demo.web.dto.PendingLimitChangeResponse
import io.github.sepfins.limitguard.demo.web.dto.ScenarioResponse
import io.github.sepfins.limitguard.demo.web.error.DemoNotFoundException
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.util.UUID

@Service
class CustomerBankingService(
    private val limitguard: Limitguard,
    private val scenarioService: DemoScenarioService,
    private val repository: DemoBankRepository,
) {
    fun scenario(): ScenarioResponse {
        val scenario = scenarioService.current()
        return ScenarioResponse(
            scenarioKey = scenario.scenarioKey,
            customer = CUSTOMER,
            accountIds = repository.listAccounts(scenario.id).map { it.id },
            cardIds = repository.listCards(scenario.id).map { it.id },
        )
    }

    fun overview(): OverviewResponse {
        val scenario = scenarioService.current()
        val totals = repository.allowedPaymentTotals(scenario.id)
        val accounts = repository.listAccounts(scenario.id).map { it.toResponse(totals) }
        val card = repository.listCards(scenario.id).firstOrNull()?.toResponse()
            ?: error("demo card is missing")
        return OverviewResponse(
            customer = CUSTOMER,
            totalBookedBalance = money(accounts.sumOf { it.bookedBalance.decimal() }),
            totalAvailableBalance = money(accounts.sumOf { it.availableBalance.decimal() }),
            accounts = accounts,
            primaryCard = card,
            recentPayments = repository.listPayments(scenario.id, limit = 6).map { it.toSummary() },
            limits = limits(),
            refreshedAt = Instant.now(),
        )
    }

    fun accounts(): List<AccountResponse> {
        val scenario = scenarioService.current()
        val totals = repository.allowedPaymentTotals(scenario.id)
        return repository.listAccounts(scenario.id).map { it.toResponse(totals) }
    }

    fun account(accountId: UUID): AccountResponse {
        val scenario = scenarioService.current()
        val account = repository.findAccount(scenario.id, accountId)
            ?: throw DemoNotFoundException("Account not found")
        return account.toResponse(repository.allowedPaymentTotals(scenario.id))
    }

    fun cards(): List<CardResponse> =
        repository.listCards(scenarioService.current().id).map { it.toResponse() }

    fun card(cardId: UUID): CardResponse {
        val scenario = scenarioService.current()
        return repository.findCard(scenario.id, cardId)?.toResponse()
            ?: throw DemoNotFoundException("Card not found")
    }

    fun payments(operation: DemoOperation? = null): List<PaymentSummaryResponse> =
        repository.listPayments(scenarioService.current().id, operation = operation).map { it.toSummary() }

    fun payment(externalPaymentId: String): PaymentDetailResponse {
        val payment = repository.findPayment(externalPaymentId)
            ?.takeIf { it.scenarioId == scenarioService.current().id }
            ?: throw DemoNotFoundException("Payment not found")
        val timeline = payment.paymentTransactionId?.let { transactionId ->
            limitguard.limitInquiryService.getTransactionHistory(
                GetTransactionHistoryQuery(
                    tenantId = requireNotNull(scenarioService.current().tenantId),
                    paymentTransactionId = transactionId,
                ),
            ).events.map { event ->
                PaymentTimelineItemResponse(
                    type = event.eventType.name,
                    label = event.eventType.displayLabel(),
                    amount = event.amount?.let { MoneyDto(decimal(it), event.currency ?: payment.currency) },
                    occurredAt = event.createdAt,
                )
            }
        }.orEmpty().ifEmpty {
            listOf(
                PaymentTimelineItemResponse(
                    type = payment.decision?.name ?: "PROCESSING",
                    label = when (payment.decision) {
                        AuthorizationDecision.DENIED -> "Declined"
                        AuthorizationDecision.NOT_APPLICABLE -> "Not applicable"
                        AuthorizationDecision.ALLOWED -> "Authorized"
                        null -> "Processing"
                    },
                    amount = money(payment.requestedAmount, payment.currency),
                    occurredAt = payment.createdAt,
                ),
            )
        }
        return PaymentDetailResponse(
            payment = payment.toSummary(),
            authorizedAmount = money(payment.authorizedAmount, payment.currency),
            capturedAmount = money(payment.capturedAmount, payment.currency),
            uncapturedAmount = money(payment.uncapturedAmount, payment.currency),
            violations = payment.violations.map { violation ->
                PaymentViolationResponse(
                    code = violation.code,
                    message = violation.message,
                    limitRuleId = violation.limitRuleId,
                    limitAmount = violation.limitAmount?.let { money(it, payment.currency) },
                    attemptedAmount = money(violation.attemptedAmount, payment.currency),
                    remainingAmount = violation.remainingAmount?.let { money(it, payment.currency) },
                )
            },
            timeline = timeline,
        )
    }

    fun limits(): List<LimitResponse> {
        val scenario = scenarioService.current()
        val tenantId = requireNotNull(scenario.tenantId)
        val customerId = requireNotNull(scenario.customerPartyProfileId)
        val fundingSourceId = currentAccountFundingSourceId(scenario.id)
        val settings = repository.listLimitSettings(scenario.id)
        val capacities = settings
            .distinctBy { it.operation }
            .associate { setting ->
                setting.operation to setting.capacity(tenantId, customerId, fundingSourceId)
            }
        return settings.map { setting -> setting.toResponse(tenantId, capacities.getValue(setting.operation)) }
    }

    fun limit(settingId: UUID): LimitResponse {
        val scenario = scenarioService.current()
        val setting = repository.findLimitSetting(scenario.id, settingId)
            ?: throw DemoNotFoundException("Limit setting not found")
        val tenantId = requireNotNull(scenario.tenantId)
        val capacity = setting.capacity(
            tenantId,
            requireNotNull(scenario.customerPartyProfileId),
            currentAccountFundingSourceId(scenario.id),
        )
        return setting.toResponse(tenantId, capacity)
    }

    private fun currentAccountFundingSourceId(scenarioId: UUID): UUID =
        repository.listAccounts(scenarioId).first { it.accountType == AccountType.CURRENT }.fundingSourceId

    private fun DemoLimitSetting.capacity(tenantId: UUID, customerId: UUID, fundingSourceId: UUID): OperationCapacityResult =
        limitguard.limitInquiryService.getOperationCapacity(
            GetOperationCapacityQuery(
                tenantId = tenantId,
                actingPartyProfileId = customerId,
                fundingSourceId = fundingSourceId,
                operationTypeId = operationTypeId,
                channelId = channelId,
                currency = "EUR",
            ),
        )

    private fun DemoLimitSetting.toResponse(tenantId: UUID, capacity: OperationCapacityResult): LimitResponse {
        val rule = limitguard.limitInquiryService.getLimitRule(GetLimitRuleQuery(tenantId, limitRuleId))
        val usage = capacity.applicableLimits.firstOrNull { it.limitRuleId == limitRuleId }
        val consumed = usage?.consumedAmount ?: BigDecimal.ZERO
        val reserved = usage?.reservedAmount ?: BigDecimal.ZERO
        val available = usage?.availableAmount ?: rule.amount
        val exposure = consumed + reserved
        val editableMinimum = if (rule.limitKind == LimitKind.PERIODIC) {
            exposure
                .divide(stepAmount, 0, RoundingMode.CEILING)
                .multiply(stepAmount)
                .max(minimumAmount)
        } else {
            minimumAmount
        }
        return LimitResponse(
            id = id,
            operation = operation,
            displayName = displayName,
            limitKind = rule.limitKind,
            period = rule.period,
            currentAmount = money(rule.amount, rule.currency),
            minimumAmount = money(editableMinimum, rule.currency),
            maximumAmount = money(maximumAmount, rule.currency),
            stepAmount = money(stepAmount, rule.currency),
            consumedAmount = money(consumed, rule.currency),
            reservedAmount = money(reserved, rule.currency),
            availableAmount = money(available.max(BigDecimal.ZERO), rule.currency),
            currentExposure = money(exposure, rule.currency),
            capacityStatus = usage?.let {
                if (available.signum() == 0) OperationCapacityStatus.EXHAUSTED else OperationCapacityStatus.AVAILABLE
            } ?: capacity.status,
            resetsAt = usage?.periodEndsAt,
            version = rule.version,
            agreedMaximum = agreedMaximumAmount?.let { money(it) },
            pendingChange = pendingChangeRequestId?.let { changeId ->
                PendingLimitChangeResponse(changeId, money(requireNotNull(pendingChangeAmount)), pendingChangeEffectiveAt)
            },
        )
    }

    private fun DemoAccount.toResponse(totals: Map<UUID, DemoPaymentTotals>): AccountResponse {
        val captured = totals[id]?.captured ?: BigDecimal.ZERO
        val held = totals[id]?.uncaptured ?: BigDecimal.ZERO
        val booked = openingBalance - captured
        return AccountResponse(
            id = id,
            accountType = accountType,
            displayName = displayName,
            maskedIban = maskedIban,
            accessLabel = accessLabel,
            bookedBalance = money(booked, currency),
            availableBalance = money(booked - held, currency),
        )
    }

    private fun DemoCard.toResponse(): CardResponse =
        CardResponse(
            id = id,
            accountId = accountId,
            displayName = displayName,
            lastFour = lastFour,
            expiresMonth = expiresMonth,
            expiresYear = expiresYear,
            status = status,
            network = network,
        )

    private fun DemoPayment.toSummary(): PaymentSummaryResponse =
        PaymentSummaryResponse(
            externalPaymentId = externalPaymentId,
            accountId = accountId,
            cardId = cardId,
            operation = operation,
            description = description,
            category = category,
            amount = money(requestedAmount, currency),
            decision = decision,
            displayStatus = displayStatus(),
            transactionStatus = transactionStatus,
            createdAt = createdAt,
            updatedAt = updatedAt,
        )

    private fun DemoPayment.displayStatus(): String =
        when {
            processingState.name == "PROCESSING" -> "PROCESSING"
            decision == AuthorizationDecision.DENIED -> "DECLINED"
            decision == AuthorizationDecision.NOT_APPLICABLE -> "NOT_APPLICABLE"
            transactionStatus == TransactionStatus.AUTHORIZED -> "PENDING"
            transactionStatus == TransactionStatus.PARTIALLY_CAPTURED -> "PARTIALLY_COMPLETED"
            transactionStatus == TransactionStatus.CAPTURED -> "COMPLETED"
            transactionStatus == TransactionStatus.VOIDED -> "RELEASED"
            transactionStatus == TransactionStatus.REVERSED -> "REVERSED"
            transactionStatus == TransactionStatus.EXPIRED -> "EXPIRED"
            else -> decision?.name ?: "PROCESSING"
        }

    private fun TransactionEventType.displayLabel(): String =
        when (this) {
            TransactionEventType.AUTHORIZATION -> "Authorized"
            TransactionEventType.CAPTURE -> "Captured"
            TransactionEventType.PARTIAL_CAPTURE -> "Partially captured"
            TransactionEventType.VOID -> "Released"
            TransactionEventType.REVERSAL -> "Reversed"
            TransactionEventType.EXPIRY -> "Expired"
            TransactionEventType.ADJUSTMENT -> "Adjusted"
        }

    private fun MoneyDto.decimal(): BigDecimal = BigDecimal(amount)

    private fun money(amount: BigDecimal, currency: String = "EUR"): MoneyDto =
        MoneyDto(decimal(amount), currency)

    private fun decimal(amount: BigDecimal): String =
        amount.setScale(2, RoundingMode.HALF_UP).toPlainString()

    private companion object {
        val CUSTOMER = CustomerDto(
            displayName = "Emma Clarke",
            initials = "EC",
            relationship = "Personal and household banking",
        )
    }
}
