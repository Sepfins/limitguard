package io.github.sepfins.limitguard.admin.web.error

data class AdminErrorResponse(
    val code: String,
    val message: String,
    val correlationId: String,
    val fieldViolations: List<AdminFieldViolation> = emptyList(),
)

data class AdminFieldViolation(
    val field: String,
    val message: String,
)
