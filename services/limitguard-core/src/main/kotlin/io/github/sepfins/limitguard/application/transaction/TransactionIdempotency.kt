package io.github.sepfins.limitguard.application.transaction

import io.github.sepfins.limitguard.api.AuthorizationDecision
import io.github.sepfins.limitguard.api.LimitUsageEventType
import io.github.sepfins.limitguard.api.TransactionStatus
import io.github.sepfins.limitguard.api.LimitKind as ApiLimitKind
import io.github.sepfins.limitguard.api.LimitPeriod as ApiLimitPeriod
import io.github.sepfins.limitguard.api.results.AuthorizationResult
import io.github.sepfins.limitguard.api.results.UsageMutationEvent
import io.github.sepfins.limitguard.api.results.MatchedLimitRule as MatchedLimitRuleResult
import io.github.sepfins.limitguard.api.results.UsageMutationResult
import io.github.sepfins.limitguard.domain.common.TransactionEventId
import io.github.sepfins.limitguard.domain.limit.LimitKind
import io.github.sepfins.limitguard.domain.limit.MatchedLimitRule
import io.github.sepfins.limitguard.domain.transaction.PaymentTransaction
import io.github.sepfins.limitguard.domain.transaction.TransactionResultSnapshot
import java.math.BigDecimal
import java.util.UUID

internal object TransactionIdempotency {
    fun authorizationResultSnapshot(
        transaction: PaymentTransaction,
        transactionEventId: TransactionEventId,
        matchedRules: List<MatchedLimitRule>,
    ): TransactionResultSnapshot =
        TransactionResultSnapshot(
            mapOf(
                "snapshotVersion" to AUTHORIZATION_SNAPSHOT_VERSION,
                "tenantId" to transaction.tenantId.value.toString(),
                "decision" to "ALLOWED",
                "paymentTransactionId" to transaction.id.value.toString(),
                "transactionEventId" to transactionEventId.value.toString(),
                "authorizedAmount" to transaction.authorizedAmount.amount.toPlainString(),
                "currency" to transaction.authorizedAmount.currency.value,
                "matchedRules" to matchedRules.map { it.toSnapshot() },
                "violations" to emptyList<Map<String, Any?>>(),
                "metadata" to transaction.metadata.value,
            ),
        )

    fun replayAuthorization(snapshot: TransactionResultSnapshot): AuthorizationResult {
        val values = snapshot.value
        val snapshotVersion = values.requiredNumber("snapshotVersion").toInt()
        check(snapshotVersion == AUTHORIZATION_SNAPSHOT_VERSION) {
            "Unsupported authorization result snapshot version: $snapshotVersion"
        }

        val decision = values.requiredEnum<AuthorizationDecision>("decision")
        check(decision == AuthorizationDecision.ALLOWED) {
            "Authorization result snapshot version $snapshotVersion only supports allowed decisions"
        }
        check(values.requiredList("violations").isEmpty()) {
            "Allowed authorization result snapshot must not contain violations"
        }

        return AuthorizationResult(
            tenantId = values.requiredUuid("tenantId"),
            decision = decision,
            paymentTransactionId = values.requiredUuid("paymentTransactionId"),
            transactionEventId = values.requiredUuid("transactionEventId"),
            authorizedAmount = values.requiredDecimal("authorizedAmount"),
            currency = values.requiredString("currency"),
            matchedRules = values.requiredList("matchedRules").map { rawRule ->
                val rule = rawRule as? Map<*, *>
                    ?: error("Authorization result snapshot contains an invalid matched rule")
                MatchedLimitRuleResult(
                    id = rule.requiredUuid("id"),
                    name = rule.requiredString("name"),
                    limitKind = rule.requiredEnum<ApiLimitKind>("limitKind"),
                    period = rule.optionalEnum<ApiLimitPeriod>("period"),
                    amount = rule.requiredDecimal("amount"),
                    currency = rule.requiredString("currency"),
                    priority = rule.requiredNumber("priority").toInt(),
                )
            },
            violations = emptyList(),
            metadata = values.requiredStringKeyedMap("metadata"),
        )
    }
    fun replayCapture(snapshot: TransactionResultSnapshot): UsageMutationResult {
        val values = snapshot.value
        val snapshotVersion = values.requiredNumber("snapshotVersion").toInt()
        check(snapshotVersion == USAGE_MUTATION_SNAPSHOT_VERSION) {
            "Unsupported usage mutation result snapshot version: $snapshotVersion"
        }

        return UsageMutationResult(
            tenantId = values.requiredUuid("tenantId"),
            paymentTransactionId = values.requiredUuid("paymentTransactionId"),
            transactionEventId = values.requiredUuid("transactionEventId"),
            status = values.requiredEnum<TransactionStatus>("status"),
            amount = values.requiredDecimal("amount"),
            currency = values.requiredString("currency"),
            usageEvents = values.requiredList("usageEvents").map { rawEvent ->
                val event = rawEvent as? Map<*, *>
                    ?: error("Usage mutation result snapshot contains an invalid usage event")
                UsageMutationEvent(
                    limitRuleId = event.requiredUuid("limitRuleId"),
                    usageBucketId = event.optionalUuid("usageBucketId"),
                    eventType = event.requiredEnum<LimitUsageEventType>("eventType"),
                    amountDelta = event.requiredDecimal("amountDelta"),
                    currency = event.requiredString("currency"),
                )
            },
            metadata = values.requiredStringKeyedMap("metadata"),
        )
    }
    fun usageMutationResultSnapshot(result: UsageMutationResult): TransactionResultSnapshot =
        TransactionResultSnapshot(
            mapOf(
                "snapshotVersion" to USAGE_MUTATION_SNAPSHOT_VERSION,
                "tenantId" to result.tenantId.toString(),
                "paymentTransactionId" to result.paymentTransactionId.toString(),
                "transactionEventId" to result.transactionEventId?.toString(),
                "status" to result.status.name,
                "amount" to result.amount.toPlainString(),
                "currency" to result.currency,
                "usageEvents" to result.usageEvents.map { event ->
                    mapOf(
                        "limitRuleId" to event.limitRuleId.toString(),
                        "usageBucketId" to event.usageBucketId?.toString(),
                        "eventType" to event.eventType.name,
                        "amountDelta" to event.amountDelta.toPlainString(),
                        "currency" to event.currency,
                    )
                },
                "metadata" to result.metadata,
            ),
        )

    private fun MatchedLimitRule.toSnapshot(): Map<String, Any?> =
        mapOf(
            "id" to id.value.toString(),
            "name" to name.value,
            "limitKind" to when (limitKind) {
                LimitKind.PerTransaction -> "PER_TRANSACTION"
                is LimitKind.Periodic -> "PERIODIC"
            },
            "period" to (limitKind as? LimitKind.Periodic)?.period?.name,
            "amount" to amount.amount.toPlainString(),
            "currency" to amount.currency.value,
            "priority" to priority.value,
        )

    private fun Map<*, *>.requiredString(key: String): String =
        this[key] as? String
            ?: error("Transaction result snapshot has no string '$key'")

    private fun Map<*, *>.requiredNumber(key: String): Number =
        this[key] as? Number
            ?: error("Transaction result snapshot has no number '$key'")

    private fun Map<*, *>.requiredDecimal(key: String): BigDecimal =
        requiredString(key).toBigDecimalOrNull()
            ?: error("Transaction result snapshot has an invalid decimal '$key'")

    private fun Map<*, *>.requiredUuid(key: String): UUID =
        runCatching { UUID.fromString(requiredString(key)) }
            .getOrElse { error("Transaction result snapshot has an invalid UUID '$key'") }

    private fun Map<*, *>.optionalUuid(key: String): UUID?  {
        val value = this[key] ?: return null
        check(value is String) {
            "Transaction result snapshot has a non-string '$key'"
        }
        return runCatching { UUID.fromString(value) }
            .getOrElse { error("Transaction result snapshot has an invalid UUID '$key'") }
    }

    private fun Map<*, *>.requiredList(key: String): List<*> =
        this[key] as? List<*>
            ?: error("Transaction result snapshot has no list '$key'")

    private fun Map<*, *>.requiredStringKeyedMap(key: String): Map<String, Any?> {
        val map = this[key] as? Map<*, *>
            ?: error("Transaction result snapshot has no object '$key'")
        check(map.keys.all { it is String }) {
            "Transaction result snapshot object '$key' contains a non-string key"
        }
        @Suppress("UNCHECKED_CAST")
        return map as Map<String, Any?>
    }

    private inline fun <reified T : Enum<T>> Map<*, *>.requiredEnum(key: String): T =
        enumValueOfOrNull<T>(requiredString(key))
            ?: error("Transaction result snapshot has an invalid ${T::class.simpleName} '$key'")

    private inline fun <reified T : Enum<T>> Map<*, *>.optionalEnum(key: String): T? {
        val value = this[key] ?: return null
        check(value is String) {
            "Transaction result snapshot has a non-string '${key}'"
        }
        return enumValueOfOrNull<T>(value)
            ?: error("Transaction result snapshot has an invalid ${T::class.simpleName} '$key'")
    }

    private inline fun <reified T : Enum<T>> enumValueOfOrNull(value: String): T? =
        enumValues<T>().firstOrNull { it.name == value }

    private const val AUTHORIZATION_SNAPSHOT_VERSION = 1
    private const val USAGE_MUTATION_SNAPSHOT_VERSION = 1
}
