package io.github.sepfins.limitguard.port.events

import io.github.sepfins.limitguard.domain.common.TenantId
import java.util.UUID
import java.time.Instant

interface DomainEvent {
    val eventId: UUID
    val tenantId: TenantId
    val occurredAt: Instant
    val eventType: String
    val aggregateType: String
    val aggregateId: UUID
    val metadata: Map<String, Any?>
}