package io.github.sepfins.limitguard.domain.transaction

enum class TransactionEventType {
    AUTHORIZATION,
    CAPTURE,
    PARTIAL_CAPTURE,
    VOID,
    REVERSAL,
    EXPIRY,
    ADJUSTMENT
}
