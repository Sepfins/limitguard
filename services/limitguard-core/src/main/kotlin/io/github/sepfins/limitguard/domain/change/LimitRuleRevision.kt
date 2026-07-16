package io.github.sepfins.limitguard.domain.change

import io.github.sepfins.limitguard.domain.common.LimitChangeRequestId
import io.github.sepfins.limitguard.domain.common.LimitRuleId
import io.github.sepfins.limitguard.domain.common.LimitRuleRevisionId
import io.github.sepfins.limitguard.domain.common.Metadata
import io.github.sepfins.limitguard.domain.common.TenantId
import java.time.Instant

data class LimitRuleRevision(
    val id: LimitRuleRevisionId,
    val tenantId: TenantId,
    val limitRuleId: LimitRuleId,
    val revisionNumber: RevisionNumber,
    val revisionEventType: LimitRuleRevisionEventType,
    val actor: ChangeActor = ChangeActor.System,
    val changeRequestId: LimitChangeRequestId? = null,
    val beforeRule: Metadata? = null,
    val afterRule: Metadata? = null,
    val effectiveAt: Instant? = null,
    val metadata: Metadata = Metadata.EMPTY,
    val createdAt: Instant? = null
) {
    init {
        require(beforeRule != null || afterRule != null) {
            "a revision must capture a before and/or after snapshot"
        }
    }
}
