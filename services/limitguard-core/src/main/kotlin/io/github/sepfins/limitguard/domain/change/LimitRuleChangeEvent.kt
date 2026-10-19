package io.github.sepfins.limitguard.domain.change

import io.github.sepfins.limitguard.domain.common.LimitChangePolicyId
import io.github.sepfins.limitguard.domain.common.LimitChangeRequestId
import io.github.sepfins.limitguard.domain.common.LimitRuleChangeEventId
import io.github.sepfins.limitguard.domain.common.Metadata
import io.github.sepfins.limitguard.domain.common.TenantId
import java.time.Instant

data class LimitRuleChangeEvent(
    val id: LimitRuleChangeEventId,
    val tenantId: TenantId,
    val changeRequestId: LimitChangeRequestId,
    val eventType: LimitRuleChangeEventType,
    val actor: ChangeActor = ChangeActor.System,
    val policyId: LimitChangePolicyId? = null,
    val beforeStatus: LimitRuleChangeStatus? = null,
    val afterStatus: LimitRuleChangeStatus? = null,
    val eventPayload: Metadata = Metadata.EMPTY,
    val message: String? = null,
    val createdAt: Instant? = null
)
