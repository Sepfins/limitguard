package io.github.sepfins.limitguard.api.commands

import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

data class AuthorizeTransactionCommand(
    val tenantId: UUID,
    val actingPartyProfileId: UUID,
    val fundingSourceId: UUID,
    val operationTypeId: UUID,
    val channelId: UUID,
    val amount: BigDecimal,
    val currency: String,
    val idempotencyKey: String? = null,
    val expiresAt: Instant? = null,
    val metadata: Map<String, Any?> = emptyMap(),
)

data class CaptureTransactionCommand(
    val tenantId: UUID,
    val paymentTransactionId: UUID,
    val amount: BigDecimal? = null,
    val reason: String? = null,
    val idempotencyKey: String? = null,
    val metadata: Map<String, Any?> = emptyMap(),
)

data class ReleaseAuthorizationCommand(
    val tenantId: UUID,
    val paymentTransactionId: UUID,
    val amount: BigDecimal? = null,
    val reason: String? = null,
    val idempotencyKey: String? = null,
    val metadata: Map<String, Any?> = emptyMap(),
)

data class ReverseTransactionCommand(
    val tenantId: UUID,
    val paymentTransactionId: UUID,
    val amount: BigDecimal? = null,
    val reason: String? = null,
    val idempotencyKey: String? = null,
    val metadata: Map<String, Any?> = emptyMap(),
)

data class ExpireDueAuthorizationsCommand(
    val tenantId: UUID,
    val batchSize: Int = 100,
)
