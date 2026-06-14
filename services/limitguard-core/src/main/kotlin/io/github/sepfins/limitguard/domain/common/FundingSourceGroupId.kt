package io.github.sepfins.limitguard.domain.common

import java.util.UUID

@JvmInline
value class FundingSourceGroupId(val id: UUID) {
    companion object {
        fun of(id: String) : FundingSourceGroupId = FundingSourceGroupId(UUID.fromString(id))
    }
}
