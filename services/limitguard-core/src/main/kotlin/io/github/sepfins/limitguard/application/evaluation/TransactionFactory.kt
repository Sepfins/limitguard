package io.github.sepfins.limitguard.application.evaluation

import io.github.sepfins.limitguard.api.commands.AuthorizeTransactionCommand
import io.github.sepfins.limitguard.domain.common.ChannelId
import io.github.sepfins.limitguard.domain.common.CurrencyCode
import io.github.sepfins.limitguard.domain.common.FundingSourceId
import io.github.sepfins.limitguard.domain.common.IdempotencyKey
import io.github.sepfins.limitguard.domain.common.Metadata
import io.github.sepfins.limitguard.domain.common.Money
import io.github.sepfins.limitguard.domain.common.OperationTypeId
import io.github.sepfins.limitguard.domain.common.PartyProfileId
import io.github.sepfins.limitguard.domain.common.PaymentTransactionId
import io.github.sepfins.limitguard.domain.common.TenantId
import io.github.sepfins.limitguard.domain.transaction.PaymentTransaction
import io.github.sepfins.limitguard.domain.transaction.TransactionStatus
import io.github.sepfins.limitguard.port.ids.IdGenerator
import java.time.Instant

internal class TransactionFactory(
    private val idGenerator: IdGenerator,
) {
    fun authorized(
        command: AuthorizeTransactionCommand,
        now: Instant,
    ): PaymentTransaction {
        val currency = CurrencyCode.of(command.currency)
        return PaymentTransaction(
            id = PaymentTransactionId.of(idGenerator.newId()),
            tenantId = TenantId.of(command.tenantId),
            actingPartyProfileId = PartyProfileId.of(command.actingPartyProfileId),
            fundingSourceId = FundingSourceId.of(command.fundingSourceId),
            operationTypeId = OperationTypeId.of(command.operationTypeId),
            channelId = ChannelId.of(command.channelId),
            authorizedAmount = Money.positive(command.amount, currency),
            capturedAmount = Money.zero(currency),
            status = TransactionStatus.AUTHORIZED,
            idempotencyKey = command.idempotencyKey?.let { IdempotencyKey.of(it) },
            expiresAt = command.expiresAt,
            metadata = Metadata(command.metadata),
            createdAt = now,
        )
    }
}
