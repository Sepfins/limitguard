package io.github.sepfins.limitguard.application.evaluation

import io.github.sepfins.limitguard.api.commands.CaptureTransactionCommand
import io.github.sepfins.limitguard.api.commands.ReleaseAuthorizationCommand
import io.github.sepfins.limitguard.api.commands.ReverseTransactionCommand
import io.github.sepfins.limitguard.application.transaction.TransactionMutationKind
import io.github.sepfins.limitguard.domain.common.PaymentTransactionId
import io.github.sepfins.limitguard.domain.common.TenantId
import java.math.BigDecimal

internal data class MutationEventCommand(
    val tenantId: TenantId,
    val transactionId: PaymentTransactionId,
    val amount: BigDecimal?,
    val idempotencyKeyRaw: String?,
    val reason: String?,
    val metadata: Map<String, Any?>,
    val mutationKind: TransactionMutationKind,
)

internal fun CaptureTransactionCommand.toMutationEventCommand(): MutationEventCommand =
    MutationEventCommand(
        tenantId = TenantId.of(tenantId),
        transactionId = PaymentTransactionId.of(paymentTransactionId),
        amount = amount,
        idempotencyKeyRaw = idempotencyKey,
        reason = reason,
        metadata = metadata,
        mutationKind = TransactionMutationKind.CAPTURE,
    )

internal fun ReleaseAuthorizationCommand.toMutationEventCommand(): MutationEventCommand =
    MutationEventCommand(
        tenantId = TenantId.of(tenantId),
        transactionId = PaymentTransactionId.of(paymentTransactionId),
        amount = amount,
        idempotencyKeyRaw = idempotencyKey,
        reason = reason,
        metadata = metadata,
        mutationKind = TransactionMutationKind.RELEASE,
    )

internal fun ReverseTransactionCommand.toMutationEventCommand(): MutationEventCommand =
    MutationEventCommand(
        tenantId = TenantId.of(tenantId),
        transactionId = PaymentTransactionId.of(paymentTransactionId),
        amount = amount,
        idempotencyKeyRaw = idempotencyKey,
        reason = reason,
        metadata = metadata,
        mutationKind = TransactionMutationKind.REVERSAL,
    )
