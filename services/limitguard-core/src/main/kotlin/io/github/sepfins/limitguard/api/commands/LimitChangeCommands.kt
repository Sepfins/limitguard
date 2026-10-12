package io.github.sepfins.limitguard.api.commands

import io.github.sepfins.limitguard.api.LimitChangeActorType
import io.github.sepfins.limitguard.api.LimitChannelScope
import io.github.sepfins.limitguard.api.LimitKind
import io.github.sepfins.limitguard.api.LimitMandateScope
import io.github.sepfins.limitguard.api.LimitPeriod
import io.github.sepfins.limitguard.api.LimitRuleChangeType
import io.github.sepfins.limitguard.api.LimitUsageConsumptionMode
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

data class RequestLimitRuleChangeCommand(
    val tenantId: UUID,
    val changeType: LimitRuleChangeType,
    val requestedBy: LimitChangeActorCommand,
    val targetOwnerPartyProfileId: UUID,
    val requestedRule: LimitRuleDraft?,
    val limitRuleId: UUID? = null,
    val expectedVersion: Int? = null,
    val requesterPartyProfileId: UUID? = null,
    val authorityDelegationId: UUID? = null,
    val requestChannelId: UUID? = null,
    val targetMandateId: UUID? = null,
    val effectiveAt: Instant? = null,
    val expiresAt: Instant? = null,
    val policyContext: Map<String, Any?> = emptyMap(),
    val idempotencyKey: String? = null,
    val metadata: Map<String, Any?> = emptyMap(),
)

data class LimitChangeActorCommand(
    val actorType: LimitChangeActorType,
    val partyProfileId: UUID? = null,
)

data class LimitRuleDraft(
    val name: String,
    val limitKind: LimitKind,
    val amount: BigDecimal,
    val currency: String,
    val description: String? = null,
    val period: LimitPeriod? = null,
    val mandateScope: LimitMandateScope = LimitMandateScope.ALL_OWNER_MANDATES,
    val mandateId: UUID? = null,
    val configurationId: UUID? = null,
    val channelScope: LimitChannelScope = LimitChannelScope.ALL_CHANNELS,
    val channelId: UUID? = null,
    val channelGroupId: UUID? = null,
    val operationTypeId: UUID? = null,
    val usageConsumptionMode: LimitUsageConsumptionMode = LimitUsageConsumptionMode.CASCADING,
    val priority: Int = 100,
    val startsAt: Instant? = null,
    val endsAt: Instant? = null,
    val metadata: Map<String, Any?> = emptyMap(),
)

data class ApproveLimitRuleChangeCommand(
    val tenantId: UUID,
    val changeRequestId: UUID,
    val approvedBy: LimitChangeActorCommand,
    val approvedRule: LimitRuleDraft? = null,
    val decisionReason: String? = null,
    val metadata: Map<String, Any?> = emptyMap(),
)

data class RejectLimitRuleChangeCommand(
    val tenantId: UUID,
    val changeRequestId: UUID,
    val rejectedBy: LimitChangeActorCommand,
    val decisionReason: String,
    val metadata: Map<String, Any?> = emptyMap(),
)

data class CancelLimitRuleChangeCommand(
    val tenantId: UUID,
    val changeRequestId: UUID,
    val cancelledBy: LimitChangeActorCommand,
    val decisionReason: String? = null,
    val metadata: Map<String, Any?> = emptyMap(),
)

data class ApplyDueLimitChangesCommand(
    val tenantId: UUID,
    val dueAt: Instant? = null,
    val limit: Int = 100,
)
