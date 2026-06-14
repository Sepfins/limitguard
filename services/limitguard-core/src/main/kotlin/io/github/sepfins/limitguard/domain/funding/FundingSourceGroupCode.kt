package io.github.sepfins.limitguard.domain.funding

@JvmInline
value class FundingSourceGroupCode private constructor(val value: String) {
    companion object {
        fun of(value: String): FundingSourceGroupCode {
            val normalized = value.trim().uppercase()
            require(normalized.isNotBlank()) { "FundingSourceGroupCode cannot be blank" }
            require(normalized.length <= 100) { "FundingSourceGroupCode cannot be longer than 100 characters" }
            return FundingSourceGroupCode(normalized)
        }
    }
}
