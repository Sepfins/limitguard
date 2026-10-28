package io.github.sepfins.limitguard.application.change

import io.github.sepfins.limitguard.domain.change.LimitRuleChangeEvent
import io.github.sepfins.limitguard.domain.common.TenantId
import io.github.sepfins.limitguard.port.persistence.LimitChangeEventOutbox
import io.github.sepfins.limitguard.port.persistence.LimitRuleChangeEventRepository

/** Queues every saved event for relay in the same transaction or savepoint as the event. */
internal class RelayingLimitRuleChangeEventRepository(
    private val events: LimitRuleChangeEventRepository,
    private val outbox: LimitChangeEventOutbox,
) : LimitRuleChangeEventRepository by events {
    override fun insert(tenantId: TenantId, event: LimitRuleChangeEvent): LimitRuleChangeEvent =
        events.insert(tenantId, event).also { outbox.enqueue(tenantId, it.id) }
}
