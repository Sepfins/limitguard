package io.github.sepfins.limitguard.application.transaction

import io.github.sepfins.limitguard.api.results.UsageMutationResult
import io.github.sepfins.limitguard.domain.common.TransactionEventId
import io.github.sepfins.limitguard.domain.limit.LimitKind
import io.github.sepfins.limitguard.domain.limit.MatchedLimitRule
import io.github.sepfins.limitguard.domain.transaction.PaymentTransaction
import io.github.sepfins.limitguard.domain.transaction.TransactionResultSnapshot

internal object TransactionIdempotency {
    fun authorizationResultSnapshot(
        transaction: PaymentTransaction,
        transactionEventId: TransactionEventId,
        matchedRules: List<MatchedLimitRule>,
    ): TransactionResultSnapshot =
        TransactionResultSnapshot(
            mapOf(
                "snapshotVersion" to 1,
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

    fun usageMutationResultSnapshot(result: UsageMutationResult): TransactionResultSnapshot =
        TransactionResultSnapshot(
            mapOf(
                "snapshotVersion" to 1,
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
}
