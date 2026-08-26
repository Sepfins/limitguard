package io.github.sepfins.limitguard.api.errors

enum class LimitguardErrorCode {
    VALIDATION_FAILED,
    NOT_FOUND,
    CONFLICT,
    POLICY_DENIED,
    LIMIT_EXCEEDED,
    IDEMPOTENCY_CONFLICT,
    INFRASTRUCTURE_FAILURE,
}

open class LimitguardException(
    val errorCode: LimitguardErrorCode,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

data class FieldViolation(
    val field: String,
    val message: String,
)

class ValidationException(
    message: String,
    val violations: List<FieldViolation> = emptyList(),
    cause: Throwable? = null,
) : LimitguardException(LimitguardErrorCode.VALIDATION_FAILED, message, cause)

class NotFoundException(
    message: String,
    cause: Throwable? = null,
) : LimitguardException(LimitguardErrorCode.NOT_FOUND, message, cause)

class ConflictException(
    message: String,
    cause: Throwable? = null,
) : LimitguardException(LimitguardErrorCode.CONFLICT, message, cause)

class PolicyDeniedException(
    message: String,
    val denialCode: String? = null,
    cause: Throwable? = null,
) : LimitguardException(LimitguardErrorCode.POLICY_DENIED, message, cause)

class LimitExceededException(
    message: String,
    val details: Map<String, Any?> = emptyMap(),
    cause: Throwable? = null,
) : LimitguardException(LimitguardErrorCode.LIMIT_EXCEEDED, message, cause)

class IdempotencyConflictException(
    message: String,
    cause: Throwable? = null,
) : LimitguardException(LimitguardErrorCode.IDEMPOTENCY_CONFLICT, message, cause)

class InfrastructureException(
    message: String,
    cause: Throwable? = null,
) : LimitguardException(LimitguardErrorCode.INFRASTRUCTURE_FAILURE, message, cause)
