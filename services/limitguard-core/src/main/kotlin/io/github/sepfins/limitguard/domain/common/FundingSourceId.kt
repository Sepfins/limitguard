package io.github.sepfins.limitguard.domain.common

import java.util.UUID

@JvmInline
value class FundingSourceId(val value: UUID) {
    companion object {
        fun of(value: UUID): FundingSourceId = FundingSourceId(value)

        fun of(value: String): FundingSourceId = of(UUID.fromString(value))
    }
}
