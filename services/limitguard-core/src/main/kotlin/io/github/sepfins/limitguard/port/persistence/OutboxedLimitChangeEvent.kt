package io.github.sepfins.limitguard.port.persistence

import io.github.sepfins.limitguard.domain.common.LimitRuleChangeEventId
import io.github.sepfins.limitguard.domain.common.TenantId

data class OutboxedLimitChangeEvent(
    val tenantId: TenantId,
    val eventId: LimitRuleChangeEventId,
)
