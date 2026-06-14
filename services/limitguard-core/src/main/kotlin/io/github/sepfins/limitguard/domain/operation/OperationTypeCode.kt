package io.github.sepfins.limitguard.domain.operation

@JvmInline
value class OperationTypeCode private constructor(val value: String) {
    companion object {
        fun of(code: String): OperationTypeCode {
            val normalized = code.trim().uppercase()

            require(normalized.isNotBlank()) {
                "Operation type code cannot be blank"
            }

            require(normalized.length <= 100) {
                "Operation type code cannot exceed 100 characters."
            }

            return OperationTypeCode(normalized)
        }
    }
}
