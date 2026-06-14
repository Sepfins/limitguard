package io.github.sepfins.limitguard.domain.worker

@JvmInline
value class BranchCode private constructor(val value: String) {
    companion object {
        fun of(value: String): BranchCode {
            val normalized = value.trim().uppercase()
            require(normalized.isNotBlank()) { "BranchCode cannot be blank" }
            require(normalized.length <= 100) { "BranchCode cannot be longer than 100 characters" }
            return BranchCode(normalized)
        }
    }
}
