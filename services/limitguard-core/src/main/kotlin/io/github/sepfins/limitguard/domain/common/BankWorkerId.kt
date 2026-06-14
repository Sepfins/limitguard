package io.github.sepfins.limitguard.domain.common

import java.util.UUID

@JvmInline
value class BankWorkerId(val id: UUID) {
    companion object {
        fun of(id: String): BankWorkerId = BankWorkerId(UUID.fromString(id))
    }
}
