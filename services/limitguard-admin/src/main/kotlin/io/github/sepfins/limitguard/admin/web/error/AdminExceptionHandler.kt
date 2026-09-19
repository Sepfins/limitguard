package io.github.sepfins.limitguard.admin.web.error

import io.github.sepfins.limitguard.admin.web.CorrelationIdFilter
import io.github.sepfins.limitguard.api.errors.InfrastructureException
import io.github.sepfins.limitguard.api.errors.LimitguardErrorCode
import io.github.sepfins.limitguard.api.errors.LimitguardException
import io.github.sepfins.limitguard.api.errors.ValidationException
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.ConstraintViolationException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.AccessDeniedException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.http.converter.HttpMessageNotReadableException

@RestControllerAdvice
class AdminExceptionHandler {
    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun invalidBody(
        exception: MethodArgumentNotValidException,
        request: HttpServletRequest,
    ): ResponseEntity<AdminErrorResponse> = error(
        status = HttpStatus.BAD_REQUEST,
        code = LimitguardErrorCode.VALIDATION_FAILED.name,
        message = "Request validation failed",
        request = request,
        violations = exception.bindingResult.fieldErrors.map { fieldError ->
            AdminFieldViolation(fieldError.field, fieldError.defaultMessage ?: "is invalid")
        },
    )

    @ExceptionHandler(ConstraintViolationException::class)
    fun invalidParameters(
        exception: ConstraintViolationException,
        request: HttpServletRequest,
    ): ResponseEntity<AdminErrorResponse> = error(
        status = HttpStatus.BAD_REQUEST,
        code = LimitguardErrorCode.VALIDATION_FAILED.name,
        message = "Request validation failed",
        request = request,
        violations = exception.constraintViolations.map { violation ->
            AdminFieldViolation(violation.propertyPath.toString(), violation.message)
        },
    )

    @ExceptionHandler(
        HttpMessageNotReadableException::class,
        MethodArgumentTypeMismatchException::class,
        MissingServletRequestParameterException::class,
    )
    fun malformedRequest(
        exception: Exception,
        request: HttpServletRequest,
    ): ResponseEntity<AdminErrorResponse> {
        val field = when (exception) {
            is MethodArgumentTypeMismatchException -> exception.name
            is MissingServletRequestParameterException -> exception.parameterName
            else -> "request"
        }
        return error(
            status = HttpStatus.BAD_REQUEST,
            code = LimitguardErrorCode.VALIDATION_FAILED.name,
            message = "Request validation failed",
            request = request,
            violations = listOf(AdminFieldViolation(field, "has an invalid value")),
        )
    }

    @ExceptionHandler(AccessDeniedException::class)
    fun forbidden(
        request: HttpServletRequest,
    ): ResponseEntity<AdminErrorResponse> = error(
        status = HttpStatus.FORBIDDEN,
        code = "FORBIDDEN",
        message = "The requested operation is not allowed",
        request = request,
    )

    @ExceptionHandler(LimitguardException::class)
    fun limitguardFailure(
        exception: LimitguardException,
        request: HttpServletRequest,
    ): ResponseEntity<AdminErrorResponse> {
        val status = when (exception.errorCode) {
            LimitguardErrorCode.VALIDATION_FAILED -> HttpStatus.BAD_REQUEST
            LimitguardErrorCode.NOT_FOUND -> HttpStatus.NOT_FOUND
            LimitguardErrorCode.CONFLICT,
            LimitguardErrorCode.IDEMPOTENCY_CONFLICT,
            -> HttpStatus.CONFLICT
            LimitguardErrorCode.POLICY_DENIED -> HttpStatus.FORBIDDEN
            LimitguardErrorCode.INFRASTRUCTURE_FAILURE -> HttpStatus.SERVICE_UNAVAILABLE
            else -> HttpStatus.BAD_REQUEST
        }
        val message = if (exception is InfrastructureException) {
            logger.error(
                "LimitGuard infrastructure failure, correlationId={}",
                correlationId(request),
                exception,
            )
            SERVICE_UNAVAILABLE_MESSAGE
        } else {
            exception.message ?: "The LimitGuard operation failed"
        }
        val violations = (exception as? ValidationException)?.violations.orEmpty().map { violation ->
            AdminFieldViolation(violation.field, violation.message)
        }
        return error(status, exception.errorCode.name, message, request, violations)
    }

    @ExceptionHandler(Exception::class)
    fun unexpectedFailure(
        exception: Exception,
        request: HttpServletRequest,
    ): ResponseEntity<AdminErrorResponse> {
        logger.error("Unhandled Admin API failure, correlationId={}", correlationId(request), exception)
        return error(
            status = HttpStatus.INTERNAL_SERVER_ERROR,
            code = "INTERNAL_ERROR",
            message = "The request could not be completed",
            request = request,
        )
    }

    private fun error(
        status: HttpStatus,
        code: String,
        message: String,
        request: HttpServletRequest,
        violations: List<AdminFieldViolation> = emptyList(),
    ): ResponseEntity<AdminErrorResponse> = ResponseEntity.status(status).body(
        AdminErrorResponse(
            code = code,
            message = message,
            correlationId = correlationId(request),
            fieldViolations = violations,
        ),
    )

    private fun correlationId(request: HttpServletRequest): String =
        request.getAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE)?.toString() ?: "unavailable"

    private companion object {
        val logger = LoggerFactory.getLogger(AdminExceptionHandler::class.java)
        const val SERVICE_UNAVAILABLE_MESSAGE = "LimitGuard is temporarily unavailable"
    }
}
