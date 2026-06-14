package io.github.sepfins.limitguard.domain.common

import java.util.UUID

@JvmInline
value class FundingSourceId(val id: UUID) {
    companion object {
        fun of(id: String): FundingSourceId = FundingSourceId(UUID.fromString(id))
    }
}
