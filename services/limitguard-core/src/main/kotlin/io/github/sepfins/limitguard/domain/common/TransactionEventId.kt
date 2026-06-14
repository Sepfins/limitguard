package io.github.sepfins.limitguard.domain.common

import java.util.UUID

@JvmInline
value class TransactionEventId(val id: UUID) {
    companion object {
        fun of(id: UUID): TransactionEventId = TransactionEventId(id)
    }
}
