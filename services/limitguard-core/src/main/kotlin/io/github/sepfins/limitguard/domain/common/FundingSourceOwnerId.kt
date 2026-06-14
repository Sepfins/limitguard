package io.github.sepfins.limitguard.domain.common

import java.util.UUID

@JvmInline
value class FundingSourceOwnerId(val id: UUID) {
    companion object {
        fun of(id: String): FundingSourceOwnerId = FundingSourceOwnerId(UUID.fromString(id))
    }
}
