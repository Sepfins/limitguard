package io.github.sepfins.limitguard.domain.common

import java.util.UUID

@JvmInline
value class FundingSourceOwnerId(val value: UUID) {
    companion object {
        fun of(value: UUID): FundingSourceOwnerId = FundingSourceOwnerId(value)

        fun of(value: String): FundingSourceOwnerId = of(UUID.fromString(value))
    }
}
