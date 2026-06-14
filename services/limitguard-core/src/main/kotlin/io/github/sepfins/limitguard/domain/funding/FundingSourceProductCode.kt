package io.github.sepfins.limitguard.domain.funding

@JvmInline
value class FundingSourceProductCode private constructor(val value: String) {
    companion object {
        fun of(value: String): FundingSourceProductCode {
            val normalized = value.trim().uppercase()
            require(normalized.isNotBlank()) { "FundingSourceProductCode cannot be blank" }
            require(normalized.length <= 100) { "FundingSourceProductCode cannot exceed 100 characters" }
            return FundingSourceProductCode(normalized)
        }
    }
}
