package io.github.sepfins.limitguard.domain.funding

@JvmInline
value class FundingSourceGroupName private constructor(val value: String) {
    companion object {
        fun of(value: String): FundingSourceGroupName {
            val normalized = value.trim()
            require(normalized.isNotBlank()) { "FundingSourceGroupName cannot be blank" }
            require(normalized.length <= 255) { "FundingSourceGroupName cannot be longer than 255 characters" }
            return FundingSourceGroupName(normalized)
        }
    }
}
