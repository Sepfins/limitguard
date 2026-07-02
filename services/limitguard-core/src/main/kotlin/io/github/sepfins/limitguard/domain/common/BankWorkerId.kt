package io.github.sepfins.limitguard.domain.common

import java.util.UUID

@JvmInline
value class BankWorkerId(val value: UUID) {
    companion object {
        fun of(value: UUID): BankWorkerId = BankWorkerId(value)

        fun of(value: String): BankWorkerId = of(UUID.fromString(value))
    }
}
