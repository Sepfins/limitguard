package io.github.sepfins.limitguard.application.transaction

import io.github.sepfins.limitguard.application.evaluation.ResolvedFundingAuthority
import io.github.sepfins.limitguard.domain.common.CurrencyCode
import io.github.sepfins.limitguard.domain.common.IdempotencyKey
import io.github.sepfins.limitguard.domain.common.LimitRuleId
import io.github.sepfins.limitguard.domain.common.Metadata
import io.github.sepfins.limitguard.domain.common.Money
import io.github.sepfins.limitguard.domain.common.TransactionEventId
import io.github.sepfins.limitguard.domain.limit.LimitKind
import io.github.sepfins.limitguard.domain.limit.LimitPeriod
import io.github.sepfins.limitguard.domain.limit.LimitPriority
import io.github.sepfins.limitguard.domain.limit.LimitRuleName
import io.github.sepfins.limitguard.domain.limit.MatchedLimitRule
import io.github.sepfins.limitguard.domain.transaction.PaymentTransaction
import io.github.sepfins.limitguard.domain.transaction.TransactionEvent
import io.github.sepfins.limitguard.domain.transaction.TransactionEventType
import io.github.sepfins.limitguard.port.ids.IdGenerator
import io.github.sepfins.limitguard.port.persistence.TransactionEventRepository
import io.github.sepfins.limitguard.port.persistence.TransactionRepository
import java.time.Instant
import java.util.UUID

internal data class RecordedAuthorization(
    val transaction: PaymentTransaction,
    val event: TransactionEvent,
    val matchedRules: List<MatchedLimitRule>,
)

internal class TransactionRecorder(
    private val transactionRepository: TransactionRepository,
    private val transactionEventRepository: TransactionEventRepository,
    private val idGenerator: IdGenerator,
) {
    fun recordAuthorization(
        transaction: PaymentTransaction,
        matchedRules: List<MatchedLimitRule>,
        authorities: List<ResolvedFundingAuthority>,
        idempotencyKey: IdempotencyKey?,
        recordedAt: Instant,
    ): RecordedAuthorization {
        val persistedTransaction = transactionRepository.insert(transaction.tenantId, transaction)
        val eventId = TransactionEventId.of(idGenerator.newId())
        val resultSnapshot = idempotencyKey?.let {
            TransactionIdempotency.authorizationResultSnapshot(
                transaction = persistedTransaction,
                transactionEventId = eventId,
                matchedRules = matchedRules,
            )
        }
        val event = transactionEventRepository.insert(
            transaction.tenantId,
            TransactionEvent(
                id = eventId,
                tenantId = transaction.tenantId,
                paymentTransactionId = persistedTransaction.id,
                eventType = TransactionEventType.AUTHORIZATION,
                amount = persistedTransaction.authorizedAmount,
                idempotencyKey = idempotencyKey,
                resultSnapshot = resultSnapshot,
                metadata = authorizationMetadata(matchedRules, authorities),
                createdAt = recordedAt,
            ),
        )
        return RecordedAuthorization(
            transaction = persistedTransaction,
            event = event,
            matchedRules = matchedRules,
        )
    }

    fun replayAuthorization(transaction: PaymentTransaction): RecordedAuthorization {
        val event = transactionEventRepository
            .findByTransaction(transaction.tenantId, transaction.id)
            .singleOrNull { it.eventType == TransactionEventType.AUTHORIZATION }
            ?: error("Persisted transaction ${transaction.id.value} has no unique authorization event")

        return RecordedAuthorization(
            transaction = transaction,
            event = event,
            matchedRules = event.metadata.readMatchedRules(),
        )
    }

    private fun authorizationMetadata(
        matchedRules: List<MatchedLimitRule>,
        authorities: List<ResolvedFundingAuthority>,
    ): Metadata =
        Metadata(
            mapOf(
                SNAPSHOT_VERSION_KEY to 1,
                MATCHED_RULES_KEY to matchedRules.map { rule ->
                    mapOf(
                        "id" to rule.id.value.toString(),
                        "name" to rule.name.value,
                        "limitKind" to rule.limitKind.kindName(),
                        "period" to rule.limitKind.periodName(),
                        "amount" to rule.amount.amount.toPlainString(),
                        "currency" to rule.amount.currency.value,
                        "priority" to rule.priority.value,
                    )
                },
                AUTHORITIES_KEY to authorities.map { authority ->
                    mapOf(
                        "ownerPartyProfileId" to authority.ownerPartyProfileId.value.toString(),
                        "directOwnership" to authority.directOwnership,
                        "mandateIds" to authority.mandateIds
                            .map { it.value.toString() }
                            .sorted(),
                    )
                },
            ),
        )

    private fun Metadata.readMatchedRules(): List<MatchedLimitRule> {
        val snapshotVersion = (value[SNAPSHOT_VERSION_KEY] as? Number)?.toInt()
        check(snapshotVersion == 1) {
            "Authorization event contains unsupported snapshot version: $snapshotVersion"
        }

        return (value[MATCHED_RULES_KEY] as? List<*>)
            .orEmpty()
            .map { rawRule ->
                val rule = rawRule as? Map<*, *>
                    ?: error("Authorization event contains an invalid matched-rule snapshot")
                val period = rule["period"] as? String
                MatchedLimitRule(
                    id = LimitRuleId.of(rule.requiredString("id")),
                    name = LimitRuleName.of(rule.requiredString("name")),
                    limitKind = when (rule.requiredString("limitKind")) {
                        "PER_TRANSACTION" -> LimitKind.PerTransaction
                        "PERIODIC" -> LimitKind.Periodic(
                            LimitPeriod.valueOf(
                                period ?: error("Periodic matched-rule snapshot has no period"),
                            ),
                        )
                        else -> error("Authorization event contains an unknown limit kind")
                    },
                    amount = Money.positive(
                        rule.requiredString("amount"),
                        CurrencyCode.of(rule.requiredString("currency")),
                    ),
                    priority = LimitPriority.of(rule.requiredNumber("priority").toInt()),
                )
            }
    }

    private fun Map<*, *>.requiredString(key: String): String =
        this[key] as? String
            ?: error("Authorization event matched-rule snapshot has no string '$key'")

    private fun Map<*, *>.requiredNumber(key: String): Number =
        this[key] as? Number
            ?: error("Authorization event matched-rule snapshot has no number '$key'")

    private fun LimitKind.kindName(): String =
        when (this) {
            LimitKind.PerTransaction -> "PER_TRANSACTION"
            is LimitKind.Periodic -> "PERIODIC"
        }

    private fun LimitKind.periodName(): String? =
        when (this) {
            LimitKind.PerTransaction -> null
            is LimitKind.Periodic -> period.name
        }

    private companion object {
        const val SNAPSHOT_VERSION_KEY = "authorizationSnapshotVersion"
        const val MATCHED_RULES_KEY = "matchedRules"
        const val AUTHORITIES_KEY = "fundingAuthorities"
    }
}
