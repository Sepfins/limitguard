package io.github.sepfins.limitguard.domain.transaction

@JvmInline
value class TransactionEventReason private constructor(val value: String) {
    companion object {
        fun of(value: String): TransactionEventReason {
            val normalized = value.trim()
            require(normalized.isNotBlank()) { "TransactionEventReason cannot be blank" }
            require(normalized.length <= 255) { "TransactionEventReason cannot be longer than 255 characters" }
            return TransactionEventReason(normalized)
        }
    }
}
