package io.github.sepfins.limitguard.domain.transaction

enum class TransactionStatus {
    AUTHORIZED,
    CAPTURED,
    PARTIALLY_CAPTURED,
    VOIDED,
    REVERSED,
    EXPIRED,
}