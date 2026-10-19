package io.github.sepfins.limitguard.domain.change

import io.github.sepfins.limitguard.domain.common.LimitChangeRequestId
import io.github.sepfins.limitguard.domain.common.LimitRuleId
import io.github.sepfins.limitguard.domain.common.LimitRuleRevisionId
import io.github.sepfins.limitguard.domain.common.Metadata
import io.github.sepfins.limitguard.domain.common.TenantId
import io.github.sepfins.limitguard.domain.limit.LimitAmount
import java.time.Instant

data class LimitRuleRevision(
    val id: LimitRuleRevisionId,
    val tenantId: TenantId,
    val limitRuleId: LimitRuleId,
    val revisionNumber: RevisionNumber,
    val revisionEventType: LimitRuleRevisionEventType,
    val actor: ChangeActor = ChangeActor.System,
    val changeRequestId: LimitChangeRequestId? = null,
    val beforeAmount: LimitAmount? = null,
    val afterAmount: LimitAmount,
    val effectiveAt: Instant? = null,
    val metadata: Metadata = Metadata.EMPTY,
    val createdAt: Instant? = null
)
