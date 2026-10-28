package io.github.sepfins.limitguard.api.commands

import io.github.sepfins.limitguard.api.LimitAmount
import io.github.sepfins.limitguard.api.LimitChangeActorType
import io.github.sepfins.limitguard.api.LimitChangeEventSink
import java.time.Instant
import java.util.UUID

/**
 * Asks for one [operation] on the owner's rule for [target]. Core looks the rule up by
 * target; [expectedVersion], when given, must equal the version found.
 */
data class RequestLimitRuleChangeCommand(
    val tenantId: UUID,
    val requestedBy: LimitChangeActorCommand,
    val target: LimitRuleTargetCommand,
    val operation: LimitRuleChangeOperation,
    val requesterPartyProfileId: UUID? = null,
    val authorityDelegationId: UUID? = null,
    val requestChannelId: UUID? = null,
    val policyContext: Map<String, Any?> = emptyMap(),
    val idempotencyKey: String? = null,
    val metadata: Map<String, Any?> = emptyMap(),
    val effectiveAt: Instant? = null,
    val expiresAt: Instant? = null,
    val expectedVersion: Int? = null,
)

/** An owner's rule is identified by its configuration and, optionally, one of the owner's mandates. */
data class LimitRuleTargetCommand(
    val ownerPartyProfileId: UUID,
    val configurationId: UUID,
    val mandateId: UUID? = null,
)

sealed interface LimitRuleChangeOperation {
    /** Creates the rule when the target has none; otherwise changes only its amount. */
    data class SetAmount(val amount: LimitAmount) : LimitRuleChangeOperation

    data object Deactivate : LimitRuleChangeOperation

    data object Reactivate : LimitRuleChangeOperation

    data object Delete : LimitRuleChangeOperation
}

data class LimitChangeActorCommand(
    val actorType: LimitChangeActorType,
    val partyProfileId: UUID? = null,
)

data class ApproveLimitRuleChangeCommand(
    val tenantId: UUID,
    val changeRequestId: UUID,
    val approvedBy: LimitChangeActorCommand,
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

/** Relays the oldest queued limit-change events of every tenant to [sink]. */
data class RelayLimitChangeEventsCommand(
    val sink: LimitChangeEventSink,
    val limit: Int = 100,
)
