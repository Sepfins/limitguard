package io.github.sepfins.limitguard.domain.common

import java.util.UUID

@JvmInline
value class FundingSourceGroupId(val value: UUID) {
    companion object {
        fun of(value: UUID): FundingSourceGroupId = FundingSourceGroupId(value)

        fun of(value: String): FundingSourceGroupId = of(UUID.fromString(value))
    }
}
