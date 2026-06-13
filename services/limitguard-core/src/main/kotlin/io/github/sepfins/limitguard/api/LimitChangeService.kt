package io.github.sepfins.limitguard.api

import io.github.sepfins.limitguard.api.commands.ApplyDueLimitChangesCommand
import io.github.sepfins.limitguard.api.commands.ApproveLimitRuleChangeCommand
import io.github.sepfins.limitguard.api.commands.CancelLimitRuleChangeCommand
import io.github.sepfins.limitguard.api.commands.RejectLimitRuleChangeCommand
import io.github.sepfins.limitguard.api.commands.RequestLimitRuleChangeCommand
import io.github.sepfins.limitguard.api.results.ApplyDueChangesResult
import io.github.sepfins.limitguard.api.results.LimitChangeRequestResult

interface LimitChangeService {
    fun requestLimitRuleChange(command: RequestLimitRuleChangeCommand): LimitChangeRequestResult

    fun approveLimitRuleChange(command: ApproveLimitRuleChangeCommand): LimitChangeRequestResult

    fun rejectLimitRuleChange(command: RejectLimitRuleChangeCommand): LimitChangeRequestResult

    fun cancelLimitRuleChange(command: CancelLimitRuleChangeCommand): LimitChangeRequestResult

    fun applyDueLimitChanges(command: ApplyDueLimitChangesCommand): ApplyDueChangesResult
}
