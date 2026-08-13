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
    fun `release reduces only uncaptured authorization`() {
        val transaction = authorizedTransaction()
            .capture(Money.positive("25", eur))
            .release(Money.positive("75", eur))

        assertEquals(TransactionStatus.CAPTURED, transaction.status)
        assertEquals(Money.of("25", eur), transaction.authorizedAmount)
        assertEquals(Money.of("25", eur), transaction.capturedAmount)
    }

    @Test
    fun `full release voids an uncaptured authorization`() {
        val transaction = authorizedTransaction()
            .release(Money.positive("100", eur))

        assertEquals(TransactionStatus.VOIDED, transaction.status)
        assertEquals(Money.zero(eur), transaction.authorizedAmount)
        assertEquals(Money.zero(eur), transaction.capturedAmount)
    }

    @Test
    fun `partial and full reversal reduce captured amount`() {
        val partiallyReversed = authorizedTransaction()
            .capture(Money.positive("100", eur))
            .reverse(Money.positive("40", eur))

        assertEquals(TransactionStatus.CAPTURED, partiallyReversed.status)
        assertEquals(Money.of("60", eur), partiallyReversed.authorizedAmount)
        assertEquals(Money.of("60", eur), partiallyReversed.capturedAmount)

        val fullyReversed = partiallyReversed.reverse()

        assertEquals(TransactionStatus.REVERSED, fullyReversed.status)
        assertEquals(Money.zero(eur), fullyReversed.authorizedAmount)
        assertEquals(Money.zero(eur), fullyReversed.capturedAmount)
    }

    @Test
    fun `terminal transaction statuses cannot transition again`() {
        val reversed = authorizedTransaction()
            .capture(Money.positive("100", eur))
            .reverse()

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
