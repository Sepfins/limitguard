package io.github.sepfins.limitguard.port.events

interface LimitguardEventPublisher {
    fun publish(event: DomainEvent)

    fun publishAll(events: Collection<DomainEvent>) {
        events.forEach(::publish)
    }
}