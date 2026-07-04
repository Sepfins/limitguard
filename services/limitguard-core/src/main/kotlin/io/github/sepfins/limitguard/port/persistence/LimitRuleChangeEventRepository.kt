package io.github.sepfins.limitguard.port.persistence

import io.github.sepfins.limitguard.domain.change.LimitRuleChangeEvent
import io.github.sepfins.limitguard.domain.common.LimitChangeRequestId
import io.github.sepfins.limitguard.domain.common.LimitRuleChangeEventId
import io.github.sepfins.limitguard.domain.common.TenantId

interface LimitRuleChangeEventRepository {
    fun findById(tenantId: TenantId, id: LimitRuleChangeEventId): LimitRuleChangeEvent?
    fun findByChangeRequest(tenantId: TenantId, changeRequestId: LimitChangeRequestId): List<LimitRuleChangeEvent>
    fun insert(tenantId: TenantId, event: LimitRuleChangeEvent): LimitRuleChangeEvent
}
