package io.github.sepfins.limitguard.domain.change

import io.github.sepfins.limitguard.domain.common.EffectiveWindow
import io.github.sepfins.limitguard.domain.common.LimitChangePolicyId
import io.github.sepfins.limitguard.domain.common.LimitConfigurationId
import io.github.sepfins.limitguard.domain.common.Metadata
import io.github.sepfins.limitguard.domain.common.TenantId
import io.github.sepfins.limitguard.domain.limit.LimitPriority
import java.time.Instant

data class LimitChangePolicy(
    val id: LimitChangePolicyId,
    val tenantId: TenantId,
    val code: LimitChangePolicyCode,
    val name: LimitChangePolicyName,
    val phase: LimitChangePolicyPhase,
    val effectiveWindow: EffectiveWindow,
    val description: String? = null,
    val engine: LimitChangePolicyEngine = LimitChangePolicyEngine.JSON_LOGIC,
    val decision: LimitChangePolicyDecision = LimitChangePolicyDecision.NO_OP,
    val limitConfigurationId: LimitConfigurationId? = null,
    val priority: LimitPriority = LimitPriority.of(100),
    val isActive: Boolean = true,
    val appliesTo: Metadata = Metadata.EMPTY,
    val conditionExpression: Metadata = Metadata.EMPTY,
    val actionExpression: List<Map<String, Any?>> = emptyList(),
    val denialCode: String? = null,
    val denialMessage: String? = null,
    val metadata: Metadata = Metadata.EMPTY,
    val createdAt: Instant? = null,
    val updatedAt: Instant? = null
)
