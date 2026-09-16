package io.github.sepfins.limitguard.demo.application

import io.github.sepfins.limitguard.api.AuthorizationDecision
import io.github.sepfins.limitguard.demo.domain.AccountType
import io.github.sepfins.limitguard.demo.domain.DemoOperation
import io.github.sepfins.limitguard.demo.domain.PaymentCategory
import io.github.sepfins.limitguard.demo.persistence.DemoBankRepository
import io.github.sepfins.limitguard.demo.web.dto.PaymentAuthorizationRequest
import io.github.sepfins.limitguard.demo.web.dto.PaymentMutationRequest
import org.springframework.stereotype.Service

/**
 * Gives the scenario some activity by sending payments through the same integration path as
 * Payment Lab, so balances, holds, and limit usage stay consistent. Fixed references and
 * idempotency keys make a restart replay the stored results instead of paying twice.
 */
@Service
class DemoActivitySeeder(
    private val scenarioService: DemoScenarioService,
    private val repository: DemoBankRepository,
    private val paymentProcessingService: PaymentProcessingService,
) {
    fun seed() {
        val scenario = scenarioService.current()
        val account = repository.listAccounts(scenario.id).first { it.accountType == AccountType.CURRENT }
        val card = repository.listCards(scenario.id).first { it.accountId == account.id }

        SEED_PAYMENTS.forEach { seed ->
            val authorization = paymentProcessingService.authorize(
                PaymentAuthorizationRequest(
                    externalPaymentId = seed.reference,
                    operation = seed.operation,
                    accountId = account.id,
                    cardId = card.id.takeIf { seed.operation.requiresCard },
                    description = seed.description,
                    category = seed.category,
                    amount = seed.amount,
                    currency = account.currency,
                ),
                idempotencyKey = "${seed.reference}-authorization",
            )
            if (seed.captured && authorization.decision == AuthorizationDecision.ALLOWED) {
                paymentProcessingService.capture(
                    seed.reference,
                    PaymentMutationRequest(amount = seed.amount, reason = "Settled"),
                    idempotencyKey = "${seed.reference}-capture",
                )
            }
        }
    }

    private data class SeedPayment(
        val reference: String,
        val operation: DemoOperation,
        val description: String,
        val category: PaymentCategory,
        val amount: String,
        val captured: Boolean = true,
    )

    private companion object {
        // Oldest first, so the open hold and the decline lead the activity list.
        val SEED_PAYMENTS = listOf(
            SeedPayment("demo-seed-001", DemoOperation.CARD_PURCHASE, "Harbour Café", PaymentCategory.DINING, "7.40"),
            SeedPayment("demo-seed-002", DemoOperation.CARD_PURCHASE, "Dublin Bus", PaymentCategory.TRANSPORT, "3.00"),
            SeedPayment("demo-seed-003", DemoOperation.BANK_TRANSFER, "Electricity bill", PaymentCategory.TRANSFER, "120.00"),
            SeedPayment("demo-seed-004", DemoOperation.ATM_WITHDRAWAL, "Grafton Street ATM", PaymentCategory.CASH, "100.00"),
            SeedPayment("demo-seed-005", DemoOperation.CARD_PURCHASE, "Corner Grocer", PaymentCategory.GROCERIES, "46.80"),
            SeedPayment(
                "demo-seed-006",
                DemoOperation.CARD_PURCHASE,
                "Northside Electronics",
                PaymentCategory.SHOPPING,
                "1650.00",
                captured = false,
            ),
            SeedPayment(
                "demo-seed-007",
                DemoOperation.CARD_PURCHASE,
                "Bookshop on Dawson Street",
                PaymentCategory.SHOPPING,
                "24.99",
                captured = false,
            ),
        )
    }
}
