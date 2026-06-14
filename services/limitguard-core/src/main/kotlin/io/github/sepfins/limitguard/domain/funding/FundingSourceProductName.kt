package io.github.sepfins.limitguard.domain.funding

@JvmInline
value class FundingSourceProductName private constructor(val value: String) {
    companion object {
        fun of(value: String): FundingSourceProductName {
            val normalized = value.trim()
            require(normalized.isNotBlank()) { "FundingSourceProductName cannot be blank" }
            require(normalized.length <= 255) { "FundingSourceProductName cannot exceed 255 characters" }
            return FundingSourceProductName(normalized)
        }
    }
}
