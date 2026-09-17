package io.github.sepfins.limitguard.demo.application

import kotlin.test.Test
import kotlin.test.assertEquals
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter

class DemoEventStreamTest {
    @Test
    fun `completion removes an SSE subscriber`() {
        val events = DemoEventStream()
        val emitter = CompletionAwareEmitter()
        events.createEmitter = { emitter }
        events.connect()
        assertEquals(1, events.subscriberCount())

        emitter.completion.run()

        assertEquals(0, events.subscriberCount())
    }

    private class CompletionAwareEmitter : SseEmitter(0L) {
        lateinit var completion: Runnable

        override fun onCompletion(callback: Runnable) {
            completion = callback
        }
    }
}
