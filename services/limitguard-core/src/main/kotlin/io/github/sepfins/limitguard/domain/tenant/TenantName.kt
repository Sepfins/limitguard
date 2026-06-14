package io.github.sepfins.limitguard.domain.tenant

@JvmInline
value class TenantName private constructor(val value: String) {
    companion object {
        fun of(value: String) : TenantName {
            val normalized = value.trim()
            require(normalized.isNotBlank()) { "TenantName cannot be blank" }
            require(normalized.length <= 255) { "TenantName cannot be longer than 255 characters" }
            return TenantName(normalized)
        }
    }
}
