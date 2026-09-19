package io.github.sepfins.limitguard.admin.security

import io.github.sepfins.limitguard.admin.web.CorrelationIdFilter
import io.github.sepfins.limitguard.admin.web.error.AdminErrorResponse
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

@Component
class AdminSecurityErrorWriter(
    private val objectMapper: ObjectMapper,
) {
    fun write(
        request: HttpServletRequest,
        response: HttpServletResponse,
        status: Int,
        code: String,
        message: String,
    ) {
        response.status = status
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        objectMapper.writeValue(
            response.outputStream,
            AdminErrorResponse(
                code = code,
                message = message,
                correlationId = request.getAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE)?.toString()
                    ?: "unavailable",
            ),
        )
    }
}
