package io.github.sepfins.limitguard.domain.transaction

import io.github.sepfins.limitguard.domain.common.ChannelId
import io.github.sepfins.limitguard.domain.common.CurrencyCode
import io.github.sepfins.limitguard.domain.common.FundingSourceId
import io.github.sepfins.limitguard.domain.common.Money
import io.github.sepfins.limitguard.domain.common.OperationTypeId
import io.github.sepfins.limitguard.domain.common.PartyProfileId
import io.github.sepfins.limitguard.domain.common.PaymentTransactionId
import io.github.sepfins.limitguard.domain.common.TenantId
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PaymentTransactionTest {
    private val eur = CurrencyCode.of("EUR")

    @Test
    fun `capture supports multiple partial captures before final capture`() {
        val transaction = authorizedTransaction()
            .capture(Money.positive("25", eur))
            .capture(Money.positive("30", eur))

        assertEquals(TransactionStatus.PARTIALLY_CAPTURED, transaction.status)
        assertEquals(Money.of("55", eur), transaction.capturedAmount)

        val captured = transaction.capture(Money.positive("45", eur))

        assertEquals(TransactionStatus.CAPTURED, captured.status)
        assertEquals(Money.of("100", eur), captured.capturedAmount)
    }

    @Test
    fun `terminal transaction statuses cannot transition again`() {
        val reversed = authorizedTransaction().reverse()

        assertFailsWith<IllegalArgumentException> {
            reversed.capture(Money.positive("1", eur))
        }
    }

    private fun authorizedTransaction(): PaymentTransaction =
        PaymentTransaction(
            id = PaymentTransactionId.of(UUID.fromString("00000000-0000-0000-0000-000000000101")),
            tenantId = TenantId.of(UUID.fromString("00000000-0000-0000-0000-000000000102")),
            actingPartyProfileId = PartyProfileId.of(UUID.fromString("00000000-0000-0000-0000-000000000103")),
            fundingSourceId = FundingSourceId.of(UUID.fromString("00000000-0000-0000-0000-000000000104")),
            operationTypeId = OperationTypeId.of(UUID.fromString("00000000-0000-0000-0000-000000000105")),
            channelId = ChannelId.of(UUID.fromString("00000000-0000-0000-0000-000000000106")),
            authorizedAmount = Money.positive("100", eur),
            capturedAmount = Money.zero(eur),
            status = TransactionStatus.AUTHORIZED,
        )
}
