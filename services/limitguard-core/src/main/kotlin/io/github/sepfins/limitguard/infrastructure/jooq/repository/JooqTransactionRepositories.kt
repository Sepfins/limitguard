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
import io.github.sepfins.limitguard.infrastructure.jooq.generated.enums.AuthorizationDecisionOutcome as GeneratedAuthorizationDecisionOutcome
import io.github.sepfins.limitguard.infrastructure.jooq.generated.enums.TransactionEventType as GeneratedTransactionEventType
import io.github.sepfins.limitguard.infrastructure.jooq.generated.enums.TransactionStatus as GeneratedTransactionStatus
import io.github.sepfins.limitguard.infrastructure.jooq.generated.tables.AuthorizationDecision as GeneratedAuthorizationDecisionTable
import io.github.sepfins.limitguard.infrastructure.jooq.generated.tables.PaymentTransaction as GeneratedPaymentTransactionTable
import io.github.sepfins.limitguard.infrastructure.jooq.generated.tables.TransactionEvent as GeneratedTransactionEventTable
import io.github.sepfins.limitguard.infrastructure.jooq.jooqOperation
import io.github.sepfins.limitguard.infrastructure.jooq.toJsonb
import io.github.sepfins.limitguard.infrastructure.jooq.toMetadata
import io.github.sepfins.limitguard.infrastructure.jooq.toObjectMap
import io.github.sepfins.limitguard.port.persistence.AuthorizationDecisionRepository
import io.github.sepfins.limitguard.port.persistence.TransactionEventRepository
import io.github.sepfins.limitguard.port.persistence.TransactionRepository
import org.jooq.Record
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

class JooqTransactionRepository(
    private val context: JooqPersistenceContext,
) : TransactionRepository {
    private val table = GeneratedPaymentTransactionTable.PAYMENT_TRANSACTION

    override fun findByFundingSourceId(
        tenantId: TenantId,
        fundingSourceId: FundingSourceId,
    ): List<PaymentTransaction> = jooqOperation("find transactions by funding source") {
        selectTransactions()
            .where(table.TENANT_ID.eq(tenantId.value))
            .and(table.FUNDING_SOURCE_ID.eq(fundingSourceId.value))
            .orderBy(table.CREATED_AT, table.ID)
            .fetch { it.toTransaction() }
    }

    override fun findByActingPartyProfileId(
        tenantId: TenantId,
        actingPartyProfileId: PartyProfileId,
    ): List<PaymentTransaction> = jooqOperation("find transactions by acting party") {
        selectTransactions()
            .where(table.TENANT_ID.eq(tenantId.value))
            .and(table.ACTING_PARTY_PROFILE_ID.eq(actingPartyProfileId.value))
            .orderBy(table.CREATED_AT, table.ID)
            .fetch { it.toTransaction() }
    }

    override fun findByActingPartyProfileAndFundingSource(
        tenantId: TenantId,
        actingPartyProfileId: PartyProfileId,
        fundingSourceId: FundingSourceId,
    ): List<PaymentTransaction> = jooqOperation("find transactions by acting party and funding source") {
        selectTransactions()
            .where(table.TENANT_ID.eq(tenantId.value))
            .and(table.ACTING_PARTY_PROFILE_ID.eq(actingPartyProfileId.value))
            .and(table.FUNDING_SOURCE_ID.eq(fundingSourceId.value))
            .orderBy(table.CREATED_AT, table.ID)
            .fetch { it.toTransaction() }
    }

    override fun findById(tenantId: TenantId, id: PaymentTransactionId): PaymentTransaction? =
        findById(tenantId, id, forUpdate = false)

    override fun findByIdForUpdate(tenantId: TenantId, id: PaymentTransactionId): PaymentTransaction? {
        context.requireTransaction("locking a payment transaction row")
        return findById(tenantId, id, forUpdate = true)
    }

    override fun findIdsDueForExpiry(
        tenantId: TenantId,
        at: Instant,
        limit: Int,
    ): List<PaymentTransactionId> = jooqOperation("find transactions due for expiry") {
        require(limit > 0) { "expiry batch limit must be positive" }
        context.dsl()
            .select(table.ID)
            .from(table)
            .where(table.TENANT_ID.eq(tenantId.value))
            .and(table.EXPIRES_AT.isNotNull)
            .and(table.EXPIRES_AT.le(at.toOffsetDateTime()))
            .and(
                table.STATUS.`in`(
                    listOf(
                        GeneratedTransactionStatus.AUTHORIZED,
                        GeneratedTransactionStatus.PARTIALLY_CAPTURED,
                    )
                )
            )
            .orderBy(table.EXPIRES_AT, table.ID)
            .limit(limit)
            .fetch { PaymentTransactionId.of(requireNotNull(it.get(table.ID))) }
    }

    override fun insert(tenantId: TenantId, transaction: PaymentTransaction): PaymentTransaction =
        jooqOperation("insert payment transaction") {
            require(transaction.tenantId == tenantId) { "payment transaction tenant does not match repository tenant" }
            val insert = context.dsl()
                .insertInto(table)
                .set(table.ID, transaction.id.value)
                .set(table.TENANT_ID, tenantId.value)
                .set(table.ACTING_PARTY_PROFILE_ID, transaction.actingPartyProfileId.value)
                .set(table.FUNDING_SOURCE_ID, transaction.fundingSourceId.value)
                .set(table.OPERATION_TYPE_ID, transaction.operationTypeId.value)
                .set(table.CHANNEL_ID, transaction.channelId.value)
                .set(table.AUTHORIZED_AMOUNT, transaction.authorizedAmount.amount)
                .set(table.CAPTURED_AMOUNT, transaction.capturedAmount.amount)
                .set(table.CURRENCY, transaction.authorizedAmount.currency.value)
                .set(table.STATUS, GeneratedTransactionStatus.valueOf(transaction.status.name))
                .set(table.EXPIRES_AT, transaction.expiresAt?.toOffsetDateTime())
                .set(table.METADATA, transaction.metadata.toJsonb())
            transaction.createdAt?.let { insert.set(table.CREATED_AT, it.toOffsetDateTime()) }
            requireNotNull(insert.returning(table.fields().toList()).fetchOne()).toTransaction()
        }

    override fun update(tenantId: TenantId, transaction: PaymentTransaction): PaymentTransaction =
        jooqOperation("update payment transaction") {
            require(transaction.tenantId == tenantId) { "payment transaction tenant does not match repository tenant" }
            requireNotNull(
                context.dsl()
                    .update(table)
                    .set(table.ACTING_PARTY_PROFILE_ID, transaction.actingPartyProfileId.value)
                    .set(table.FUNDING_SOURCE_ID, transaction.fundingSourceId.value)
                    .set(table.OPERATION_TYPE_ID, transaction.operationTypeId.value)
                    .set(table.CHANNEL_ID, transaction.channelId.value)
                    .set(table.AUTHORIZED_AMOUNT, transaction.authorizedAmount.amount)
                    .set(table.CAPTURED_AMOUNT, transaction.capturedAmount.amount)
                    .set(table.CURRENCY, transaction.authorizedAmount.currency.value)
                    .set(table.STATUS, GeneratedTransactionStatus.valueOf(transaction.status.name))
                    .set(table.EXPIRES_AT, transaction.expiresAt?.toOffsetDateTime())
                    .set(table.METADATA, transaction.metadata.toJsonb())
                    .where(table.TENANT_ID.eq(tenantId.value))
                    .and(table.ID.eq(transaction.id.value))
                    .returning(table.fields().toList())
                    .fetchOne()
            ) { "payment transaction not found: ${transaction.id.value}" }.toTransaction()
        }

    private fun findById(
        tenantId: TenantId,
        id: PaymentTransactionId,
        forUpdate: Boolean,
    ): PaymentTransaction? = jooqOperation("find payment transaction by id") {
        val query = selectTransactions()
            .where(table.TENANT_ID.eq(tenantId.value))
            .and(table.ID.eq(id.value))
        if (forUpdate) query.forUpdate().fetchOne { it.toTransaction() } else query.fetchOne { it.toTransaction() }
    }

    private fun selectTransactions() = context.dsl()
        .select(
            table.ID,
            table.TENANT_ID,
            table.ACTING_PARTY_PROFILE_ID,
            table.FUNDING_SOURCE_ID,
            table.OPERATION_TYPE_ID,
            table.CHANNEL_ID,
            table.AUTHORIZED_AMOUNT,
            table.CAPTURED_AMOUNT,
            table.CURRENCY,
            table.STATUS,
            table.EXPIRES_AT,
            table.METADATA,
            table.CREATED_AT,
        )
        .from(table)

    private fun Record.toTransaction(): PaymentTransaction {
        val currency = CurrencyCode.of(requireNotNull(get(table.CURRENCY)))
        return PaymentTransaction(
            id = PaymentTransactionId.of(requireNotNull(get(table.ID))),
            tenantId = TenantId.of(requireNotNull(get(table.TENANT_ID))),
            actingPartyProfileId = PartyProfileId.of(
                requireNotNull(get(table.ACTING_PARTY_PROFILE_ID))
            ),
            fundingSourceId = FundingSourceId.of(requireNotNull(get(table.FUNDING_SOURCE_ID))),
            operationTypeId = OperationTypeId.of(requireNotNull(get(table.OPERATION_TYPE_ID))),
            channelId = ChannelId.of(requireNotNull(get(table.CHANNEL_ID))),
            authorizedAmount = Money.of(requireNotNull(get(table.AUTHORIZED_AMOUNT)), currency),
            capturedAmount = Money.of(requireNotNull(get(table.CAPTURED_AMOUNT)), currency),
            status = TransactionStatus.valueOf(requireNotNull(get(table.STATUS)).name),
            expiresAt = get(table.EXPIRES_AT)?.toInstant(),
            metadata = get(table.METADATA).toMetadata(),
            createdAt = get(table.CREATED_AT)?.toInstant(),
        )
    }

    private fun Instant.toOffsetDateTime(): OffsetDateTime = OffsetDateTime.ofInstant(this, ZoneOffset.UTC)
}

class JooqTransactionEventRepository(
    private val context: JooqPersistenceContext,
) : TransactionEventRepository {
    private val table = GeneratedTransactionEventTable.TRANSACTION_EVENT
    private val transactionTable = GeneratedPaymentTransactionTable.PAYMENT_TRANSACTION

    override fun findById(tenantId: TenantId, id: TransactionEventId): TransactionEvent? =
        jooqOperation("find transaction event by id") {
            selectEvents()
                .where(table.TENANT_ID.eq(tenantId.value))
                .and(table.ID.eq(id.value))
                .fetchOne { it.toEvent() }
        }

    override fun findByIdempotencyKey(tenantId: TenantId, key: IdempotencyKey): TransactionEvent? =
        jooqOperation("find transaction event by idempotency key") {
            selectEvents()
                .where(table.TENANT_ID.eq(tenantId.value))
                .and(table.IDEMPOTENCY_KEY.eq(key.value))
                .fetchOne { it.toEvent() }
        }

    override fun findByTransaction(
        tenantId: TenantId,
        transactionId: PaymentTransactionId,
    ): List<TransactionEvent> = jooqOperation("find events by transaction") {
        selectEvents()
            .where(table.TENANT_ID.eq(tenantId.value))
            .and(table.PAYMENT_TRANSACTION_ID.eq(transactionId.value))
            .orderBy(table.CREATED_AT, table.ID)
            .fetch { it.toEvent() }
    }

    override fun findAuthorizationEvent(
        tenantId: TenantId,
        transactionId: PaymentTransactionId,
    ): TransactionEvent? = jooqOperation("find authorization event") {
        selectEvents()
            .where(table.TENANT_ID.eq(tenantId.value))
            .and(table.PAYMENT_TRANSACTION_ID.eq(transactionId.value))
            .and(table.EVENT_TYPE.eq(GeneratedTransactionEventType.AUTHORIZATION))
            .orderBy(table.CREATED_AT, table.ID)
            .limit(1)
            .fetchOne { it.toEvent() }
    }

    override fun insert(tenantId: TenantId, event: TransactionEvent): TransactionEvent =
        jooqOperation("insert transaction event", TRANSACTION_EVENT_IDEMPOTENCY_CONSTRAINT) {
            require(event.tenantId == tenantId) { "transaction event tenant does not match repository tenant" }
            val insert = context.dsl()
                .insertInto(table)
                .set(table.ID, event.id.value)
                .set(table.TENANT_ID, tenantId.value)
                .set(table.PAYMENT_TRANSACTION_ID, event.paymentTransactionId.value)
                .set(table.EVENT_TYPE, GeneratedTransactionEventType.valueOf(event.eventType.name))
                .set(table.AMOUNT, event.amount?.amount)
                .set(table.REQUESTED_AMOUNT, event.requestedAmount?.amount)
                .set(table.REASON, event.reason?.value)
                .set(table.IDEMPOTENCY_KEY, event.idempotencyKey?.value)
                .set(table.RESULT_SNAPSHOT, event.resultSnapshot?.value?.toJsonb())
                .set(table.METADATA, event.metadata.toJsonb())
            event.createdAt?.let { insert.set(table.CREATED_AT, it.toOffsetDateTime()) }
            val currency = event.amount?.currency ?: event.requestedAmount?.currency
            requireNotNull(insert.returning().fetchOne()).toEvent(currency)
        }

    override fun completeIdempotency(
        tenantId: TenantId,
        eventId: TransactionEventId,
        idempotencyKey: IdempotencyKey,
        resultSnapshot: TransactionResultSnapshot,
    ) {
        context.requireTransaction("completing transaction event idempotency")
        jooqOperation("complete transaction event idempotency", TRANSACTION_EVENT_IDEMPOTENCY_CONSTRAINT) {
            val updated = context.dsl()
                .update(table)
                .set(table.IDEMPOTENCY_KEY, idempotencyKey.value)
                .set(table.RESULT_SNAPSHOT, resultSnapshot.value.toJsonb())
                .where(table.TENANT_ID.eq(tenantId.value))
                .and(table.ID.eq(eventId.value))
                .and(table.IDEMPOTENCY_KEY.isNull)
                .and(table.RESULT_SNAPSHOT.isNull)
                .execute()
            require(updated == 1) { "transaction event not found or idempotency already completed: ${eventId.value}" }
        }
    }

    private fun selectEvents() = context.dsl()
        .select(
            table.ID,
            table.TENANT_ID,
            table.PAYMENT_TRANSACTION_ID,
            table.EVENT_TYPE,
            table.AMOUNT,
            table.REQUESTED_AMOUNT,
            table.REASON,
            table.IDEMPOTENCY_KEY,
            table.RESULT_SNAPSHOT,
            table.METADATA,
            table.CREATED_AT,
            transactionTable.CURRENCY,
        )
        .from(table)
        .join(transactionTable)
        .on(transactionTable.TENANT_ID.eq(table.TENANT_ID))
        .and(transactionTable.ID.eq(table.PAYMENT_TRANSACTION_ID))

    private fun Record.toEvent(): TransactionEvent =
        toEvent(CurrencyCode.of(requireNotNull(get(transactionTable.CURRENCY))))

    /** [currency] is null only for a row with no amounts. */
    private fun Record.toEvent(currency: CurrencyCode?): TransactionEvent {
        val amount = get(table.AMOUNT)?.let { Money.of(it, requireNotNull(currency)) }
        val requestedAmount = get(table.REQUESTED_AMOUNT)?.let { Money.of(it, requireNotNull(currency)) }
        return TransactionEvent(
            id = TransactionEventId.of(requireNotNull(get(table.ID))),
            tenantId = TenantId.of(requireNotNull(get(table.TENANT_ID))),
            paymentTransactionId = PaymentTransactionId.of(
                requireNotNull(get(table.PAYMENT_TRANSACTION_ID))
            ),
            eventType = TransactionEventType.valueOf(requireNotNull(get(table.EVENT_TYPE)).name),
            amount = amount,
            requestedAmount = requestedAmount,
            reason = get(table.REASON)?.let(TransactionEventReason::of),
            idempotencyKey = get(table.IDEMPOTENCY_KEY)?.let(IdempotencyKey::of),
            resultSnapshot = get(table.RESULT_SNAPSHOT)?.let {
                TransactionResultSnapshot(it.toObjectMap("transaction result snapshot"))
            },
            metadata = get(table.METADATA).toMetadata(),
            createdAt = get(table.CREATED_AT)?.toInstant(),
        )
    }

    private fun Instant.toOffsetDateTime(): OffsetDateTime = OffsetDateTime.ofInstant(this, ZoneOffset.UTC)

    private companion object {
        const val TRANSACTION_EVENT_IDEMPOTENCY_CONSTRAINT = "uq_transaction_event_idempotency"
    }
}

class JooqAuthorizationDecisionRepository(
    private val context: JooqPersistenceContext,
) : AuthorizationDecisionRepository {
    private val table = GeneratedAuthorizationDecisionTable.AUTHORIZATION_DECISION

    override fun findById(
        tenantId: TenantId,
        id: AuthorizationDecisionId,
    ): AuthorizationDecisionRecord? = jooqOperation("find authorization decision by id") {
        selectDecisions()
            .where(table.TENANT_ID.eq(tenantId.value))
            .and(table.ID.eq(id.value))
            .fetchOne { it.toDecision() }
    }

    override fun findByIdempotencyKey(
        tenantId: TenantId,
        key: IdempotencyKey,
    ): AuthorizationDecisionRecord? = jooqOperation("find authorization decision by idempotency key") {
        selectDecisions()
            .where(table.TENANT_ID.eq(tenantId.value))
            .and(table.IDEMPOTENCY_KEY.eq(key.value))
            .fetchOne { it.toDecision() }
    }

    override fun findByTransaction(
        tenantId: TenantId,
        transactionId: PaymentTransactionId,
    ): AuthorizationDecisionRecord? = jooqOperation("find authorization decision by transaction") {
        selectDecisions()
            .where(table.TENANT_ID.eq(tenantId.value))
            .and(table.PAYMENT_TRANSACTION_ID.eq(transactionId.value))
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
            .insertInto(table)
            .set(table.ID, decision.id.value)
            .set(table.TENANT_ID, tenantId.value)
            .set(table.ACTING_PARTY_PROFILE_ID, decision.actingPartyProfileId.value)
            .set(table.FUNDING_SOURCE_ID, decision.fundingSourceId.value)
            .set(table.OPERATION_TYPE_ID, decision.operationTypeId.value)
            .set(table.CHANNEL_ID, decision.channelId.value)
            .set(table.REQUESTED_AMOUNT, decision.requestedAmount.amount)
            .set(table.CURRENCY, decision.requestedAmount.currency.value)
            .set(table.DECISION, GeneratedAuthorizationDecisionOutcome.valueOf(decision.decision.name))
            .set(table.PAYMENT_TRANSACTION_ID, decision.paymentTransactionId?.value)
            .set(table.TRANSACTION_EVENT_ID, decision.transactionEventId?.value)
            .set(table.IDEMPOTENCY_KEY, decision.idempotencyKey?.value)
            .set(table.RESULT_SNAPSHOT, decision.resultSnapshot.value.toJsonb())
            .set(table.EXPIRES_AT, decision.expiresAt?.toOffsetDateTime())
            .set(table.METADATA, decision.metadata.toJsonb())
        decision.createdAt?.let { insert.set(table.CREATED_AT, it.toOffsetDateTime()) }
        requireNotNull(insert.returning(table.fields().toList()).fetchOne()).toDecision()
    }

    private fun selectDecisions() = context.dsl()
        .select(
            table.ID,
            table.TENANT_ID,
            table.ACTING_PARTY_PROFILE_ID,
            table.FUNDING_SOURCE_ID,
            table.OPERATION_TYPE_ID,
            table.CHANNEL_ID,
            table.REQUESTED_AMOUNT,
            table.CURRENCY,
            table.DECISION,
            table.PAYMENT_TRANSACTION_ID,
            table.TRANSACTION_EVENT_ID,
            table.IDEMPOTENCY_KEY,
            table.RESULT_SNAPSHOT,
            table.EXPIRES_AT,
            table.METADATA,
            table.CREATED_AT,
        )
        .from(table)

    private fun Record.toDecision(): AuthorizationDecisionRecord {
        val currency = CurrencyCode.of(requireNotNull(get(table.CURRENCY)))
        return AuthorizationDecisionRecord(
            id = AuthorizationDecisionId.of(requireNotNull(get(table.ID))),
            tenantId = TenantId.of(requireNotNull(get(table.TENANT_ID))),
            actingPartyProfileId = PartyProfileId.of(
                requireNotNull(get(table.ACTING_PARTY_PROFILE_ID))
            ),
            fundingSourceId = FundingSourceId.of(requireNotNull(get(table.FUNDING_SOURCE_ID))),
            operationTypeId = OperationTypeId.of(
                requireNotNull(get(table.OPERATION_TYPE_ID))
            ),
            channelId = ChannelId.of(requireNotNull(get(table.CHANNEL_ID))),
            requestedAmount = Money.positive(
                requireNotNull(get(table.REQUESTED_AMOUNT)),
                currency,
            ),
            decision = AuthorizationDecisionOutcome.valueOf(
                requireNotNull(get(table.DECISION)).name
            ),
            paymentTransactionId = get(table.PAYMENT_TRANSACTION_ID)
                ?.let(PaymentTransactionId::of),
            transactionEventId = get(table.TRANSACTION_EVENT_ID)?.let(TransactionEventId::of),
            idempotencyKey = get(table.IDEMPOTENCY_KEY)?.let(IdempotencyKey::of),
            resultSnapshot = TransactionResultSnapshot(
                get(table.RESULT_SNAPSHOT).toObjectMap("authorization result snapshot")
            ),
            expiresAt = get(table.EXPIRES_AT)?.toInstant(),
            metadata = get(table.METADATA).toMetadata(),
            createdAt = get(table.CREATED_AT)?.toInstant(),
        )
    }

    private fun Instant.toOffsetDateTime(): OffsetDateTime = OffsetDateTime.ofInstant(this, ZoneOffset.UTC)

    private companion object {
        const val AUTHORIZATION_DECISION_IDEMPOTENCY_CONSTRAINT = "uq_authorization_decision_idempotency"
    }
}
