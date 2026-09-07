package io.github.sepfins.limitguard.infrastructure.jooq.repository

import io.github.sepfins.limitguard.domain.common.AuthorizationDecisionId
import io.github.sepfins.limitguard.domain.common.ChannelId
import io.github.sepfins.limitguard.domain.common.CurrencyCode
import io.github.sepfins.limitguard.domain.common.FundingSourceId
import io.github.sepfins.limitguard.domain.common.IdempotencyKey
import io.github.sepfins.limitguard.domain.common.Money
import io.github.sepfins.limitguard.domain.common.OperationTypeId
import io.github.sepfins.limitguard.domain.common.PartyProfileId
import io.github.sepfins.limitguard.domain.common.PaymentTransactionId
import io.github.sepfins.limitguard.domain.common.TenantId
import io.github.sepfins.limitguard.domain.common.TransactionEventId
import io.github.sepfins.limitguard.domain.transaction.AuthorizationDecisionOutcome
import io.github.sepfins.limitguard.domain.transaction.AuthorizationDecisionRecord
import io.github.sepfins.limitguard.domain.transaction.PaymentTransaction
import io.github.sepfins.limitguard.domain.transaction.TransactionEvent
import io.github.sepfins.limitguard.domain.transaction.TransactionEventReason
import io.github.sepfins.limitguard.domain.transaction.TransactionEventType
import io.github.sepfins.limitguard.domain.transaction.TransactionResultSnapshot
import io.github.sepfins.limitguard.domain.transaction.TransactionStatus
import io.github.sepfins.limitguard.infrastructure.jooq.JooqPersistenceContext
import io.github.sepfins.limitguard.infrastructure.jooq.eqPostgresEnum
import io.github.sepfins.limitguard.infrastructure.jooq.inPostgresEnums
import io.github.sepfins.limitguard.infrastructure.jooq.jooqField
import io.github.sepfins.limitguard.infrastructure.jooq.jooqOperation
import io.github.sepfins.limitguard.infrastructure.jooq.jooqTable
import io.github.sepfins.limitguard.infrastructure.jooq.postgresEnum
import io.github.sepfins.limitguard.infrastructure.jooq.toJsonb
import io.github.sepfins.limitguard.infrastructure.jooq.toMetadata
import io.github.sepfins.limitguard.infrastructure.jooq.toObjectMap
import io.github.sepfins.limitguard.port.persistence.AuthorizationDecisionRepository
import io.github.sepfins.limitguard.port.persistence.TransactionEventRepository
import io.github.sepfins.limitguard.port.persistence.TransactionRepository
import org.jooq.Field
import org.jooq.JSONB
import org.jooq.Record
import org.jooq.SelectFieldOrAsterisk
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

class JooqTransactionRepository(
    private val context: JooqPersistenceContext,
) : TransactionRepository {
    override fun findByFundingSourceId(
        tenantId: TenantId,
        fundingSourceId: FundingSourceId,
    ): List<PaymentTransaction> = jooqOperation("find transactions by funding source") {
        selectTransactions()
            .where(PaymentTransactionTable.tenantId.eq(tenantId.value))
            .and(PaymentTransactionTable.fundingSourceId.eq(fundingSourceId.value))
            .orderBy(PaymentTransactionTable.createdAt, PaymentTransactionTable.id)
            .fetch { it.toTransaction() }
    }

    override fun findByActingPartyProfileId(
        tenantId: TenantId,
        actingPartyProfileId: PartyProfileId,
    ): List<PaymentTransaction> = jooqOperation("find transactions by acting party") {
        selectTransactions()
            .where(PaymentTransactionTable.tenantId.eq(tenantId.value))
            .and(PaymentTransactionTable.actingPartyProfileId.eq(actingPartyProfileId.value))
            .orderBy(PaymentTransactionTable.createdAt, PaymentTransactionTable.id)
            .fetch { it.toTransaction() }
    }

    override fun findByActingPartyProfileAndFundingSource(
        tenantId: TenantId,
        actingPartyProfileId: PartyProfileId,
        fundingSourceId: FundingSourceId,
    ): List<PaymentTransaction> = jooqOperation("find transactions by acting party and funding source") {
        selectTransactions()
            .where(PaymentTransactionTable.tenantId.eq(tenantId.value))
            .and(PaymentTransactionTable.actingPartyProfileId.eq(actingPartyProfileId.value))
            .and(PaymentTransactionTable.fundingSourceId.eq(fundingSourceId.value))
            .orderBy(PaymentTransactionTable.createdAt, PaymentTransactionTable.id)
            .fetch { it.toTransaction() }
    }

    override fun findById(tenantId: TenantId, id: PaymentTransactionId): PaymentTransaction? =
        findById(tenantId, id, forUpdate = false)

    override fun findByIdForUpdate(tenantId: TenantId, id: PaymentTransactionId): PaymentTransaction? {
        context.requireTransaction("locking a payment transaction row")
        return findById(tenantId, id, forUpdate = true)
    }

    override fun findDueForExpiry(
        tenantId: TenantId,
        at: Instant,
        limit: Int,
    ): List<PaymentTransaction> = jooqOperation("find transactions due for expiry") {
        require(limit > 0) { "expiry batch limit must be positive" }
        selectTransactions()
            .where(PaymentTransactionTable.tenantId.eq(tenantId.value))
            .and(PaymentTransactionTable.expiresAt.isNotNull)
            .and(PaymentTransactionTable.expiresAt.le(at))
            .and(
                PaymentTransactionTable.status.inPostgresEnums(
                    listOf(TransactionStatus.AUTHORIZED.name, TransactionStatus.PARTIALLY_CAPTURED.name),
                    "transaction_status",
                )
            )
            .orderBy(PaymentTransactionTable.expiresAt, PaymentTransactionTable.id)
            .limit(limit)
            .fetch { it.toTransaction() }
    }

    override fun insert(tenantId: TenantId, transaction: PaymentTransaction): PaymentTransaction =
        jooqOperation("insert payment transaction") {
            require(transaction.tenantId == tenantId) { "payment transaction tenant does not match repository tenant" }
            val insert = context.dsl()
                .insertInto(PaymentTransactionTable.table)
                .set(PaymentTransactionTable.id, transaction.id.value)
                .set(PaymentTransactionTable.tenantId, tenantId.value)
                .set(PaymentTransactionTable.actingPartyProfileId, transaction.actingPartyProfileId.value)
                .set(PaymentTransactionTable.fundingSourceId, transaction.fundingSourceId.value)
                .set(PaymentTransactionTable.operationTypeId, transaction.operationTypeId.value)
                .set(PaymentTransactionTable.channelId, transaction.channelId.value)
                .set(PaymentTransactionTable.authorizedAmount, transaction.authorizedAmount.amount)
                .set(PaymentTransactionTable.capturedAmount, transaction.capturedAmount.amount)
                .set(PaymentTransactionTable.currency, transaction.authorizedAmount.currency.value)
                .set(PaymentTransactionTable.status, postgresEnum(transaction.status.name, "transaction_status"))
                .set(PaymentTransactionTable.expiresAt, transaction.expiresAt)
                .set(PaymentTransactionTable.metadata, transaction.metadata.toJsonb())
            transaction.createdAt?.let { insert.set(PaymentTransactionTable.createdAt, it) }
            requireNotNull(insert.returning(PaymentTransactionTable.fields).fetchOne()).toTransaction()
        }

    override fun update(tenantId: TenantId, transaction: PaymentTransaction): PaymentTransaction =
        jooqOperation("update payment transaction") {
            require(transaction.tenantId == tenantId) { "payment transaction tenant does not match repository tenant" }
            requireNotNull(
                context.dsl()
                    .update(PaymentTransactionTable.table)
                    .set(PaymentTransactionTable.actingPartyProfileId, transaction.actingPartyProfileId.value)
                    .set(PaymentTransactionTable.fundingSourceId, transaction.fundingSourceId.value)
                    .set(PaymentTransactionTable.operationTypeId, transaction.operationTypeId.value)
                    .set(PaymentTransactionTable.channelId, transaction.channelId.value)
                    .set(PaymentTransactionTable.authorizedAmount, transaction.authorizedAmount.amount)
                    .set(PaymentTransactionTable.capturedAmount, transaction.capturedAmount.amount)
                    .set(PaymentTransactionTable.currency, transaction.authorizedAmount.currency.value)
                    .set(PaymentTransactionTable.status, postgresEnum(transaction.status.name, "transaction_status"))
                    .set(PaymentTransactionTable.expiresAt, transaction.expiresAt)
                    .set(PaymentTransactionTable.metadata, transaction.metadata.toJsonb())
                    .where(PaymentTransactionTable.tenantId.eq(tenantId.value))
                    .and(PaymentTransactionTable.id.eq(transaction.id.value))
                    .returning(PaymentTransactionTable.fields)
                    .fetchOne()
            ) { "payment transaction not found: ${transaction.id.value}" }.toTransaction()
        }

    private fun findById(
        tenantId: TenantId,
        id: PaymentTransactionId,
        forUpdate: Boolean,
    ): PaymentTransaction? = jooqOperation("find payment transaction by id") {
        val query = selectTransactions()
            .where(PaymentTransactionTable.tenantId.eq(tenantId.value))
            .and(PaymentTransactionTable.id.eq(id.value))
        if (forUpdate) query.forUpdate().fetchOne { it.toTransaction() } else query.fetchOne { it.toTransaction() }
    }

    private fun selectTransactions() = context.dsl()
        .select(PaymentTransactionTable.fields)
        .from(PaymentTransactionTable.table)

    private fun Record.toTransaction(): PaymentTransaction {
        val currency = CurrencyCode.of(requireNotNull(get(PaymentTransactionTable.currency)))
        return PaymentTransaction(
            id = PaymentTransactionId.of(requireNotNull(get(PaymentTransactionTable.id))),
            tenantId = TenantId.of(requireNotNull(get(PaymentTransactionTable.tenantId))),
            actingPartyProfileId = PartyProfileId.of(
                requireNotNull(get(PaymentTransactionTable.actingPartyProfileId))
            ),
            fundingSourceId = FundingSourceId.of(requireNotNull(get(PaymentTransactionTable.fundingSourceId))),
            operationTypeId = OperationTypeId.of(requireNotNull(get(PaymentTransactionTable.operationTypeId))),
            channelId = ChannelId.of(requireNotNull(get(PaymentTransactionTable.channelId))),
            authorizedAmount = Money.of(requireNotNull(get(PaymentTransactionTable.authorizedAmount)), currency),
            capturedAmount = Money.of(requireNotNull(get(PaymentTransactionTable.capturedAmount)), currency),
            status = TransactionStatus.valueOf(requireNotNull(get(PaymentTransactionTable.status))),
            expiresAt = get(PaymentTransactionTable.expiresAt),
            metadata = get(PaymentTransactionTable.metadata).toMetadata(),
            createdAt = get(PaymentTransactionTable.createdAt),
        )
    }
}

class JooqTransactionEventRepository(
    private val context: JooqPersistenceContext,
) : TransactionEventRepository {
    override fun findById(tenantId: TenantId, id: TransactionEventId): TransactionEvent? =
        jooqOperation("find transaction event by id") {
            selectEvents()
                .where(TransactionEventTable.tenantId.eq(tenantId.value))
                .and(TransactionEventTable.id.eq(id.value))
                .fetchOne { it.toEvent() }
        }

    override fun findByIdempotencyKey(tenantId: TenantId, key: IdempotencyKey): TransactionEvent? =
        jooqOperation("find transaction event by idempotency key") {
            selectEvents()
                .where(TransactionEventTable.tenantId.eq(tenantId.value))
                .and(TransactionEventTable.idempotencyKey.eq(key.value))
                .fetchOne { it.toEvent() }
        }

    override fun findByTransaction(
        tenantId: TenantId,
        transactionId: PaymentTransactionId,
    ): List<TransactionEvent> = jooqOperation("find events by transaction") {
        selectEvents()
            .where(TransactionEventTable.tenantId.eq(tenantId.value))
            .and(TransactionEventTable.paymentTransactionId.eq(transactionId.value))
            .orderBy(TransactionEventTable.createdAt, TransactionEventTable.id)
            .fetch { it.toEvent() }
    }

    override fun findAuthorizationEvent(
        tenantId: TenantId,
        transactionId: PaymentTransactionId,
    ): TransactionEvent? = jooqOperation("find authorization event") {
        selectEvents()
            .where(TransactionEventTable.tenantId.eq(tenantId.value))
            .and(TransactionEventTable.paymentTransactionId.eq(transactionId.value))
            .and(
                TransactionEventTable.eventType.eqPostgresEnum(
                    TransactionEventType.AUTHORIZATION.name,
                    "transaction_event_type",
                )
            )
            .orderBy(TransactionEventTable.createdAt, TransactionEventTable.id)
            .limit(1)
            .fetchOne { it.toEvent() }
    }

    override fun insert(tenantId: TenantId, event: TransactionEvent): TransactionEvent =
        jooqOperation("insert transaction event", TRANSACTION_EVENT_IDEMPOTENCY_CONSTRAINT) {
            require(event.tenantId == tenantId) { "transaction event tenant does not match repository tenant" }
            val insert = context.dsl()
                .insertInto(TransactionEventTable.table)
                .set(TransactionEventTable.id, event.id.value)
                .set(TransactionEventTable.tenantId, tenantId.value)
                .set(TransactionEventTable.paymentTransactionId, event.paymentTransactionId.value)
                .set(
                    TransactionEventTable.eventType,
                    postgresEnum(event.eventType.name, "transaction_event_type"),
                )
                .set(TransactionEventTable.amount, event.amount?.amount)
                .set(TransactionEventTable.requestedAmount, event.requestedAmount?.amount)
                .set(TransactionEventTable.reason, event.reason?.value)
                .set(TransactionEventTable.idempotencyKey, event.idempotencyKey?.value)
                .set(TransactionEventTable.resultSnapshot, event.resultSnapshot?.value?.toJsonb())
                .set(TransactionEventTable.metadata, event.metadata.toJsonb())
            event.createdAt?.let { insert.set(TransactionEventTable.createdAt, it) }
            requireNotNull(insert.returning(TransactionEventTable.id).fetchOne())
            requireNotNull(findById(tenantId, event.id))
        }

    override fun completeIdempotency(
        tenantId: TenantId,
        eventId: TransactionEventId,
        idempotencyKey: IdempotencyKey,
        resultSnapshot: TransactionResultSnapshot,
    ): TransactionEvent {
        context.requireTransaction("completing transaction event idempotency")
        return jooqOperation("complete transaction event idempotency", TRANSACTION_EVENT_IDEMPOTENCY_CONSTRAINT) {
            val updated = context.dsl()
                .update(TransactionEventTable.table)
                .set(TransactionEventTable.idempotencyKey, idempotencyKey.value)
                .set(TransactionEventTable.resultSnapshot, resultSnapshot.value.toJsonb())
                .where(TransactionEventTable.tenantId.eq(tenantId.value))
                .and(TransactionEventTable.id.eq(eventId.value))
                .and(TransactionEventTable.idempotencyKey.isNull)
                .and(TransactionEventTable.resultSnapshot.isNull)
                .execute()
            require(updated == 1) { "transaction event not found or idempotency already completed: ${eventId.value}" }
            requireNotNull(findById(tenantId, eventId))
        }
    }

    private fun selectEvents() = context.dsl()
        .select(TransactionEventTable.fields + PaymentTransactionTable.currency)
        .from(TransactionEventTable.table)
        .join(PaymentTransactionTable.table)
        .on(PaymentTransactionTable.tenantId.eq(TransactionEventTable.tenantId))
        .and(PaymentTransactionTable.id.eq(TransactionEventTable.paymentTransactionId))

    private fun Record.toEvent(): TransactionEvent {
        val currency = CurrencyCode.of(requireNotNull(get(PaymentTransactionTable.currency)))
        val amount = get(TransactionEventTable.amount)?.let { Money.of(it, currency) }
        val requestedAmount = get(TransactionEventTable.requestedAmount)?.let { Money.of(it, currency) }
        return TransactionEvent(
            id = TransactionEventId.of(requireNotNull(get(TransactionEventTable.id))),
            tenantId = TenantId.of(requireNotNull(get(TransactionEventTable.tenantId))),
            paymentTransactionId = PaymentTransactionId.of(
                requireNotNull(get(TransactionEventTable.paymentTransactionId))
            ),
            eventType = TransactionEventType.valueOf(requireNotNull(get(TransactionEventTable.eventType))),
            amount = amount,
            requestedAmount = requestedAmount,
            reason = get(TransactionEventTable.reason)?.let(TransactionEventReason::of),
            idempotencyKey = get(TransactionEventTable.idempotencyKey)?.let(IdempotencyKey::of),
            resultSnapshot = get(TransactionEventTable.resultSnapshot)?.let {
                TransactionResultSnapshot(it.toObjectMap("transaction result snapshot"))
            },
            metadata = get(TransactionEventTable.metadata).toMetadata(),
            createdAt = get(TransactionEventTable.createdAt),
        )
    }

    private companion object {
        const val TRANSACTION_EVENT_IDEMPOTENCY_CONSTRAINT = "uq_transaction_event_idempotency"
    }
}

class JooqAuthorizationDecisionRepository(
    private val context: JooqPersistenceContext,
) : AuthorizationDecisionRepository {
    override fun findById(
        tenantId: TenantId,
        id: AuthorizationDecisionId,
    ): AuthorizationDecisionRecord? = jooqOperation("find authorization decision by id") {
        selectDecisions()
            .where(AuthorizationDecisionTable.tenantId.eq(tenantId.value))
            .and(AuthorizationDecisionTable.id.eq(id.value))
            .fetchOne { it.toDecision() }
    }

    override fun findByIdempotencyKey(
        tenantId: TenantId,
        key: IdempotencyKey,
    ): AuthorizationDecisionRecord? = jooqOperation("find authorization decision by idempotency key") {
        selectDecisions()
            .where(AuthorizationDecisionTable.tenantId.eq(tenantId.value))
            .and(AuthorizationDecisionTable.idempotencyKey.eq(key.value))
            .fetchOne { it.toDecision() }
    }

    override fun findByTransaction(
        tenantId: TenantId,
        transactionId: PaymentTransactionId,
    ): AuthorizationDecisionRecord? = jooqOperation("find authorization decision by transaction") {
        selectDecisions()
            .where(AuthorizationDecisionTable.tenantId.eq(tenantId.value))
            .and(AuthorizationDecisionTable.paymentTransactionId.eq(transactionId.value))
            .fetchOne { it.toDecision() }
    }

    override fun insert(
        tenantId: TenantId,
        decision: AuthorizationDecisionRecord,
    ): AuthorizationDecisionRecord = jooqOperation(
        "insert authorization decision",
        AUTHORIZATION_DECISION_IDEMPOTENCY_CONSTRAINT,
    ) {
        require(decision.tenantId == tenantId) { "authorization decision tenant does not match repository tenant" }
        val insert = context.dsl()
            .insertInto(AuthorizationDecisionTable.table)
            .set(AuthorizationDecisionTable.id, decision.id.value)
            .set(AuthorizationDecisionTable.tenantId, tenantId.value)
            .set(AuthorizationDecisionTable.actingPartyProfileId, decision.actingPartyProfileId.value)
            .set(AuthorizationDecisionTable.fundingSourceId, decision.fundingSourceId.value)
            .set(AuthorizationDecisionTable.operationTypeId, decision.operationTypeId.value)
            .set(AuthorizationDecisionTable.channelId, decision.channelId.value)
            .set(AuthorizationDecisionTable.requestedAmount, decision.requestedAmount.amount)
            .set(AuthorizationDecisionTable.currency, decision.requestedAmount.currency.value)
            .set(
                AuthorizationDecisionTable.decision,
                postgresEnum(decision.decision.name, "authorization_decision_outcome"),
            )
            .set(AuthorizationDecisionTable.paymentTransactionId, decision.paymentTransactionId?.value)
            .set(AuthorizationDecisionTable.transactionEventId, decision.transactionEventId?.value)
            .set(AuthorizationDecisionTable.idempotencyKey, decision.idempotencyKey?.value)
            .set(AuthorizationDecisionTable.resultSnapshot, decision.resultSnapshot.value.toJsonb())
            .set(AuthorizationDecisionTable.expiresAt, decision.expiresAt)
            .set(AuthorizationDecisionTable.metadata, decision.metadata.toJsonb())
        decision.createdAt?.let { insert.set(AuthorizationDecisionTable.createdAt, it) }
        requireNotNull(insert.returning(AuthorizationDecisionTable.fields).fetchOne()).toDecision()
    }

    private fun selectDecisions() = context.dsl()
        .select(AuthorizationDecisionTable.fields)
        .from(AuthorizationDecisionTable.table)

    private fun Record.toDecision(): AuthorizationDecisionRecord {
        val currency = CurrencyCode.of(requireNotNull(get(AuthorizationDecisionTable.currency)))
        return AuthorizationDecisionRecord(
            id = AuthorizationDecisionId.of(requireNotNull(get(AuthorizationDecisionTable.id))),
            tenantId = TenantId.of(requireNotNull(get(AuthorizationDecisionTable.tenantId))),
            actingPartyProfileId = PartyProfileId.of(
                requireNotNull(get(AuthorizationDecisionTable.actingPartyProfileId))
            ),
            fundingSourceId = FundingSourceId.of(requireNotNull(get(AuthorizationDecisionTable.fundingSourceId))),
            operationTypeId = OperationTypeId.of(
                requireNotNull(get(AuthorizationDecisionTable.operationTypeId))
            ),
            channelId = ChannelId.of(requireNotNull(get(AuthorizationDecisionTable.channelId))),
            requestedAmount = Money.positive(
                requireNotNull(get(AuthorizationDecisionTable.requestedAmount)),
                currency,
            ),
            decision = AuthorizationDecisionOutcome.valueOf(
                requireNotNull(get(AuthorizationDecisionTable.decision))
            ),
            paymentTransactionId = get(AuthorizationDecisionTable.paymentTransactionId)
                ?.let(PaymentTransactionId::of),
            transactionEventId = get(AuthorizationDecisionTable.transactionEventId)?.let(TransactionEventId::of),
            idempotencyKey = get(AuthorizationDecisionTable.idempotencyKey)?.let(IdempotencyKey::of),
            resultSnapshot = TransactionResultSnapshot(
                get(AuthorizationDecisionTable.resultSnapshot).toObjectMap("authorization result snapshot")
            ),
            expiresAt = get(AuthorizationDecisionTable.expiresAt),
            metadata = get(AuthorizationDecisionTable.metadata).toMetadata(),
            createdAt = get(AuthorizationDecisionTable.createdAt),
        )
    }

    private companion object {
        const val AUTHORIZATION_DECISION_IDEMPOTENCY_CONSTRAINT = "uq_authorization_decision_idempotency"
    }
}

private object PaymentTransactionTable {
    const val NAME = "payment_transaction"
    val table = jooqTable(NAME)
    val id: Field<UUID> = jooqField(NAME, "id", UUID::class.java)
    val tenantId: Field<UUID> = jooqField(NAME, "tenant_id", UUID::class.java)
    val actingPartyProfileId: Field<UUID> = jooqField(NAME, "acting_party_profile_id", UUID::class.java)
    val fundingSourceId: Field<UUID> = jooqField(NAME, "funding_source_id", UUID::class.java)
    val operationTypeId: Field<UUID> = jooqField(NAME, "operation_type_id", UUID::class.java)
    val channelId: Field<UUID> = jooqField(NAME, "channel_id", UUID::class.java)
    val authorizedAmount: Field<BigDecimal> = jooqField(NAME, "authorized_amount", BigDecimal::class.java)
    val capturedAmount: Field<BigDecimal> = jooqField(NAME, "captured_amount", BigDecimal::class.java)
    val currency: Field<String> = jooqField(NAME, "currency", String::class.java)
    val status: Field<String> = jooqField(NAME, "status", String::class.java)
    val expiresAt: Field<Instant> = jooqField(NAME, "expires_at", Instant::class.java)
    val metadata: Field<JSONB> = jooqField(NAME, "metadata", JSONB::class.java)
    val createdAt: Field<Instant> = jooqField(NAME, "created_at", Instant::class.java)
    val fields: List<SelectFieldOrAsterisk> = listOf(
        id,
        tenantId,
        actingPartyProfileId,
        fundingSourceId,
        operationTypeId,
        channelId,
        authorizedAmount,
        capturedAmount,
        currency,
        status,
        expiresAt,
        metadata,
        createdAt,
    )
}

private object TransactionEventTable {
    const val NAME = "transaction_event"
    val table = jooqTable(NAME)
    val id: Field<UUID> = jooqField(NAME, "id", UUID::class.java)
    val tenantId: Field<UUID> = jooqField(NAME, "tenant_id", UUID::class.java)
    val paymentTransactionId: Field<UUID> = jooqField(NAME, "payment_transaction_id", UUID::class.java)
    val eventType: Field<String> = jooqField(NAME, "event_type", String::class.java)
    val amount: Field<BigDecimal> = jooqField(NAME, "amount", BigDecimal::class.java)
    val requestedAmount: Field<BigDecimal> = jooqField(NAME, "requested_amount", BigDecimal::class.java)
    val reason: Field<String> = jooqField(NAME, "reason", String::class.java)
    val idempotencyKey: Field<String> = jooqField(NAME, "idempotency_key", String::class.java)
    val resultSnapshot: Field<JSONB> = jooqField(NAME, "result_snapshot", JSONB::class.java)
    val metadata: Field<JSONB> = jooqField(NAME, "metadata", JSONB::class.java)
    val createdAt: Field<Instant> = jooqField(NAME, "created_at", Instant::class.java)
    val fields: List<SelectFieldOrAsterisk> = listOf(
        id,
        tenantId,
        paymentTransactionId,
        eventType,
        amount,
        requestedAmount,
        reason,
        idempotencyKey,
        resultSnapshot,
        metadata,
        createdAt,
    )
}

private object AuthorizationDecisionTable {
    const val NAME = "authorization_decision"
    val table = jooqTable(NAME)
    val id: Field<UUID> = jooqField(NAME, "id", UUID::class.java)
    val tenantId: Field<UUID> = jooqField(NAME, "tenant_id", UUID::class.java)
    val actingPartyProfileId: Field<UUID> = jooqField(NAME, "acting_party_profile_id", UUID::class.java)
    val fundingSourceId: Field<UUID> = jooqField(NAME, "funding_source_id", UUID::class.java)
    val operationTypeId: Field<UUID> = jooqField(NAME, "operation_type_id", UUID::class.java)
    val channelId: Field<UUID> = jooqField(NAME, "channel_id", UUID::class.java)
    val requestedAmount: Field<BigDecimal> = jooqField(NAME, "requested_amount", BigDecimal::class.java)
    val currency: Field<String> = jooqField(NAME, "currency", String::class.java)
    val decision: Field<String> = jooqField(NAME, "decision", String::class.java)
    val paymentTransactionId: Field<UUID> = jooqField(NAME, "payment_transaction_id", UUID::class.java)
    val transactionEventId: Field<UUID> = jooqField(NAME, "transaction_event_id", UUID::class.java)
    val idempotencyKey: Field<String> = jooqField(NAME, "idempotency_key", String::class.java)
    val resultSnapshot: Field<JSONB> = jooqField(NAME, "result_snapshot", JSONB::class.java)
    val expiresAt: Field<Instant> = jooqField(NAME, "expires_at", Instant::class.java)
    val metadata: Field<JSONB> = jooqField(NAME, "metadata", JSONB::class.java)
    val createdAt: Field<Instant> = jooqField(NAME, "created_at", Instant::class.java)
    val fields: List<SelectFieldOrAsterisk> = listOf(
        id,
        tenantId,
        actingPartyProfileId,
        fundingSourceId,
        operationTypeId,
        channelId,
        requestedAmount,
        currency,
        decision,
        paymentTransactionId,
        transactionEventId,
        idempotencyKey,
        resultSnapshot,
        expiresAt,
        metadata,
        createdAt,
    )
}
