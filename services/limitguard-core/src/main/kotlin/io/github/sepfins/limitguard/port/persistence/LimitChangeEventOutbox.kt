package io.github.sepfins.limitguard.port.persistence

import io.github.sepfins.limitguard.domain.common.LimitRuleChangeEventId
import io.github.sepfins.limitguard.domain.common.TenantId

/** Limit-change events still owed to the host's relay sink, across every tenant. */
interface LimitChangeEventOutbox {
    fun enqueue(tenantId: TenantId, eventId: LimitRuleChangeEventId)

    /**
     * Takes the transaction-scoped relay lock without waiting and reads up to [limit] of the
     * oldest entries. Returns nothing when another relay holds the lock.
     */
    fun lockOldest(limit: Int): List<OutboxedLimitChangeEvent>

    fun delete(entries: List<OutboxedLimitChangeEvent>)
}
