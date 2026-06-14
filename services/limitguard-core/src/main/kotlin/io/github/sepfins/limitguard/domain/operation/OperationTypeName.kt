package io.github.sepfins.limitguard.domain.operation

@JvmInline
value class OperationTypeName private constructor(val value: String) {
    companion object {
        fun of(name: String): OperationTypeName {
            val normalized = name.trim()

            require(normalized.isNotBlank()) {
                "Operation type name cannot be blank"
            }

            require(normalized.length <= 255) {
                "Operation type name cannot exceed 255 characters."
            }

            return OperationTypeName(normalized)
        }
    }
}