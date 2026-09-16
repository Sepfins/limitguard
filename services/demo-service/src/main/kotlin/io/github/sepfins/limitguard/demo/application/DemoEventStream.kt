package io.github.sepfins.limitguard.demo.application

import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

data class DemoEvent(
    val resource: String,
    val resourceId: String?,
    val revision: Long,
    val occurredAt: Instant,
)

@Component
class DemoEventStream {
    private val emitters = ConcurrentHashMap<UUID, SseEmitter>()
    private val revision = AtomicLong()
    internal var createEmitter: () -> SseEmitter = { SseEmitter(0L) }

    fun connect(): SseEmitter {
        val emitterId = UUID.randomUUID()
        val emitter = createEmitter()
        emitters[emitterId] = emitter
        emitter.onCompletion { emitters.remove(emitterId) }
        emitter.onTimeout { emitters.remove(emitterId) }
        emitter.onError { emitters.remove(emitterId) }
        send(
            emitterId,
            emitter,
            name = "snapshot.required",
            event = DemoEvent("snapshot", null, revision.get(), Instant.now()),
        )
        return emitter
    }

    fun publish(name: String, resource: String, resourceId: String? = null) {
        val event = DemoEvent(resource, resourceId, revision.incrementAndGet(), Instant.now())
        emitters.forEach { (id, emitter) -> send(id, emitter, name, event) }
    }

    fun subscriberCount(): Int = emitters.size

    @Scheduled(fixedDelayString = "\${limitguard.demo.event-heartbeat:20s}")
    fun heartbeat() {
        emitters.forEach { (id, emitter) ->
            try {
                emitter.send(SseEmitter.event().comment("keepalive"))
            } catch (_: Exception) {
                emitters.remove(id)
            }
        }
    }

    private fun send(id: UUID, emitter: SseEmitter, name: String, event: DemoEvent) {
        try {
            emitter.send(
                SseEmitter.event()
                    .id(event.revision.toString())
                    .name(name)
                    .data(event),
            )
        } catch (_: Exception) {
            emitters.remove(id)
        }
    }
}
