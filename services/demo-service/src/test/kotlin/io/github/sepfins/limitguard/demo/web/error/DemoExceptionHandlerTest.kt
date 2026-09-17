package io.github.sepfins.limitguard.demo.web.error

import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.http.HttpStatus
import org.springframework.mock.web.MockHttpServletRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class DemoExceptionHandlerTest {
    @Test
    fun `sanitizes demo database failures as service unavailable`() {
        val request = MockHttpServletRequest().apply {
            setAttribute(CORRELATION_ID_ATTRIBUTE, "test-correlation")
        }

        val response = DemoExceptionHandler().databaseUnavailable(
            DataAccessResourceFailureException("jdbc:postgresql://private-host/secret"),
            request,
        )

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.statusCode)
        assertEquals("INFRASTRUCTURE_FAILURE", response.body?.code)
        assertEquals("test-correlation", response.body?.correlationId)
        assertFalse(response.body?.message.orEmpty().contains("private-host"))
    }
}
