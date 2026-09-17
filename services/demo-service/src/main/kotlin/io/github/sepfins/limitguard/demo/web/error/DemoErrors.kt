package io.github.sepfins.limitguard.demo.web.error

import io.github.sepfins.limitguard.api.errors.AuthorizationExpiredException
import io.github.sepfins.limitguard.api.errors.ConflictException
import io.github.sepfins.limitguard.api.errors.IdempotencyConflictException
import io.github.sepfins.limitguard.api.errors.InfrastructureException
import io.github.sepfins.limitguard.api.errors.LimitguardException
import io.github.sepfins.limitguard.api.errors.NotFoundException
import io.github.sepfins.limitguard.api.errors.PolicyDeniedException
import io.github.sepfins.limitguard.api.errors.ValidationException
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.ConstraintViolationException
import org.springframework.dao.DataAccessException
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.MissingRequestHeaderException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.context.request.async.AsyncRequestNotUsableException
import org.springframework.web.context.request.async.AsyncRequestTimeoutException
import java.time.Instant

data class DemoFieldError(
    val field: String,
    val message: String,
)

data class DemoErrorResponse(
    val code: String,
    val message: String,
    val correlationId: String,
    val fieldErrors: List<DemoFieldError> = emptyList(),
    val timestamp: Instant = Instant.now(),
)

open class DemoApplicationException(
    val code: String,
    message: String,
) : RuntimeException(message)

class DemoValidationException(
    message: String,
    val fieldErrors: List<DemoFieldError> = emptyList(),
) : DemoApplicationException("VALIDATION_FAILED", message)

class DemoNotFoundException(message: String) : DemoApplicationException("NOT_FOUND", message)

class DemoConflictException(message: String) : DemoApplicationException("CONFLICT", message)

class DemoPolicyDeniedException(message: String) : DemoApplicationException("POLICY_DENIED", message)

@RestControllerAdvice
class DemoExceptionHandler {
    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun invalidBody(
        exception: MethodArgumentNotValidException,
        request: HttpServletRequest,
    ): ResponseEntity<DemoErrorResponse> =
        response(
            status = HttpStatus.BAD_REQUEST,
            code = "VALIDATION_FAILED",
            message = "Request validation failed",
            request = request,
            fieldErrors = exception.bindingResult.fieldErrors.map {
                DemoFieldError(it.field, it.defaultMessage ?: "is invalid")
            },
        )

    @ExceptionHandler(
        HttpMessageNotReadableException::class,
        MissingRequestHeaderException::class,
        ConstraintViolationException::class,
    )
    fun malformedRequest(exception: Exception, request: HttpServletRequest): ResponseEntity<DemoErrorResponse> =
        response(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Request is malformed or incomplete", request)

    @ExceptionHandler(DemoApplicationException::class)
    fun demoError(
        exception: DemoApplicationException,
        request: HttpServletRequest,
    ): ResponseEntity<DemoErrorResponse> =
        when (exception) {
            is DemoValidationException -> response(
                HttpStatus.BAD_REQUEST,
                exception.code,
                exception.message ?: "Request validation failed",
                request,
                exception.fieldErrors,
            )
            is DemoNotFoundException -> response(
                HttpStatus.NOT_FOUND,
                exception.code,
                exception.message ?: "Resource not found",
                request,
            )
            is DemoConflictException -> response(
                HttpStatus.CONFLICT,
                exception.code,
                exception.message ?: "Request conflicts with current state",
                request,
            )
            is DemoPolicyDeniedException -> response(
                HttpStatus.UNPROCESSABLE_ENTITY,
                exception.code,
                exception.message ?: "The limit change was not approved",
                request,
            )
            else -> response(HttpStatus.BAD_REQUEST, exception.code, exception.message ?: "Request failed", request)
        }

    @ExceptionHandler(LimitguardException::class)
    fun limitguardError(
        exception: LimitguardException,
        request: HttpServletRequest,
    ): ResponseEntity<DemoErrorResponse> {
        val status = when (exception) {
            is ValidationException -> HttpStatus.BAD_REQUEST
            is NotFoundException -> HttpStatus.NOT_FOUND
            is ConflictException, is IdempotencyConflictException, is AuthorizationExpiredException -> HttpStatus.CONFLICT
            is PolicyDeniedException -> HttpStatus.UNPROCESSABLE_ENTITY
            is InfrastructureException -> HttpStatus.SERVICE_UNAVAILABLE
            else -> HttpStatus.BAD_REQUEST
        }
        val message = if (exception is InfrastructureException) {
            "Limit processing is temporarily unavailable"
        } else {
            exception.message ?: "LimitGuard request failed"
        }
        val fields = (exception as? ValidationException)?.violations?.map {
            DemoFieldError(it.field, it.message)
        }.orEmpty()
        return response(status, exception.errorCode.name, message, request, fields)
    }

    @ExceptionHandler(DataAccessException::class)
    fun databaseUnavailable(
        exception: DataAccessException,
        request: HttpServletRequest,
    ): ResponseEntity<DemoErrorResponse> =
        response(
            HttpStatus.SERVICE_UNAVAILABLE,
            "INFRASTRUCTURE_FAILURE",
            "Banking data is temporarily unavailable",
            request,
        )

    @ExceptionHandler(
        AsyncRequestNotUsableException::class,
        AsyncRequestTimeoutException::class,
    )
    fun asyncStreamClosed() {
        // The SSE response is already complete or unusable; there is no error body left to write.
    }

    @ExceptionHandler(
        exception = [Exception::class],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    fun unexpected(exception: Exception, request: HttpServletRequest): ResponseEntity<DemoErrorResponse> =
        response(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "INTERNAL_ERROR",
            "The request could not be completed",
            request,
        )

    private fun response(
        status: HttpStatus,
        code: String,
        message: String,
        request: HttpServletRequest,
        fieldErrors: List<DemoFieldError> = emptyList(),
    ): ResponseEntity<DemoErrorResponse> =
        ResponseEntity.status(status).body(
            DemoErrorResponse(
                code = code,
                message = message,
                correlationId = request.getAttribute(CORRELATION_ID_ATTRIBUTE)?.toString() ?: "unknown",
                fieldErrors = fieldErrors,
            ),
        )
}

const val CORRELATION_ID_ATTRIBUTE: String = "limitguard.demo.correlationId"
