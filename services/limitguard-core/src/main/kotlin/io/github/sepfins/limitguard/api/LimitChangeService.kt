package io.github.sepfins.limitguard.api

import io.github.sepfins.limitguard.api.commands.ApplyDueLimitChangesCommand
import io.github.sepfins.limitguard.api.commands.ApproveLimitRuleChangeCommand
import io.github.sepfins.limitguard.api.commands.CancelLimitRuleChangeCommand
import io.github.sepfins.limitguard.api.commands.RejectLimitRuleChangeCommand
import io.github.sepfins.limitguard.api.commands.RequestLimitRuleChangeCommand
import io.github.sepfins.limitguard.api.results.ApplyDueChangesResult
import io.github.sepfins.limitguard.api.results.LimitChangeRequestResult

/**
 * Repeated approval, rejection, or cancellation conflicts by lifecycle.
 * Hosts must authorize every invocation, including a replay.
 */
interface LimitChangeService {
    /** Creation retries return the existing request's current state. */
    fun requestLimitRuleChange(
        command: RequestLimitRuleChangeCommand,
        trustedActorContext: TrustedChangeActorContext? = null,
    ): LimitChangeRequestResult

    fun approveLimitRuleChange(
        command: ApproveLimitRuleChangeCommand,
        trustedActorContext: TrustedChangeActorContext? = null,
    ): LimitChangeRequestResult

    fun rejectLimitRuleChange(
        command: RejectLimitRuleChangeCommand,
        trustedActorContext: TrustedChangeActorContext? = null,
    ): LimitChangeRequestResult

    fun cancelLimitRuleChange(
        command: CancelLimitRuleChangeCommand,
        trustedActorContext: TrustedChangeActorContext? = null,
    ): LimitChangeRequestResult

    fun applyDueLimitChanges(
        command: ApplyDueLimitChangesCommand,
        trustedActorContext: TrustedChangeActorContext.InternalSystem,
    ): ApplyDueChangesResult
}
