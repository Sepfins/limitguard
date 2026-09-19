package io.github.sepfins.limitguard.admin.web

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.util.UUID

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class CorrelationIdFilter : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val correlationId = request.getHeader(HEADER_NAME)
            ?.takeIf { it.length <= MAX_LENGTH && it.matches(VALID_VALUE) }
            ?: UUID.randomUUID().toString()
        request.setAttribute(REQUEST_ATTRIBUTE, correlationId)
        response.setHeader(HEADER_NAME, correlationId)
        filterChain.doFilter(request, response)
    }

    companion object {
        const val HEADER_NAME = "X-Correlation-ID"
        const val REQUEST_ATTRIBUTE = "limitguard.correlationId"
        private const val MAX_LENGTH = 100
        private val VALID_VALUE = Regex("[A-Za-z0-9._:-]+")
    }
}
