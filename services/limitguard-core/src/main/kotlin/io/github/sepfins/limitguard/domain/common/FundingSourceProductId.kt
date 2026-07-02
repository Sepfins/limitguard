package io.github.sepfins.limitguard.domain.common

import java.util.UUID

@JvmInline
value class FundingSourceProductId(val value: UUID) {
    companion object {
        fun of(value: UUID): FundingSourceProductId = FundingSourceProductId(value)

        fun of(value: String): FundingSourceProductId = of(UUID.fromString(value))
    }
}
