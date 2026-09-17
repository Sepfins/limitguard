package io.github.sepfins.limitguard.demo.web

import io.github.sepfins.limitguard.demo.web.error.CORRELATION_ID_ATTRIBUTE
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.util.UUID

@Component
class CorrelationIdFilter : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val correlationId = request.getHeader(HEADER_NAME)
            ?.trim()
            ?.takeIf { it.matches(VALID_ID) }
            ?: UUID.randomUUID().toString()
        request.setAttribute(CORRELATION_ID_ATTRIBUTE, correlationId)
        response.setHeader(HEADER_NAME, correlationId)
        MDC.put("correlationId", correlationId)
        try {
            filterChain.doFilter(request, response)
        } finally {
            MDC.remove("correlationId")
        }
    }

    private companion object {
        const val HEADER_NAME = "X-Correlation-ID"
        val VALID_ID = Regex("^[A-Za-z0-9._:-]{1,100}$")
    }
}
