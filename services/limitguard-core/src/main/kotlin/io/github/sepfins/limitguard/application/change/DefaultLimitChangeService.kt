package io.github.sepfins.limitguard.application.change

import io.github.sepfins.limitguard.api.LimitChangeService
import io.github.sepfins.limitguard.api.commands.ApplyDueLimitChangesCommand
import io.github.sepfins.limitguard.api.commands.ApproveLimitRuleChangeCommand
import io.github.sepfins.limitguard.api.commands.CancelLimitRuleChangeCommand
import io.github.sepfins.limitguard.api.commands.RejectLimitRuleChangeCommand
import io.github.sepfins.limitguard.api.commands.RequestLimitRuleChangeCommand
import io.github.sepfins.limitguard.api.results.ApplyDueChangesResult
import io.github.sepfins.limitguard.api.results.LimitChangeRequestResult

internal class DefaultLimitChangeService(
    private val requestWorkflow: RequestLimitRuleChangeWorkflow,
    private val approveWorkflow: ApproveLimitRuleChangeWorkflow,
    private val rejectWorkflow: RejectLimitRuleChangeWorkflow,
    private val cancelWorkflow: CancelLimitRuleChangeWorkflow,
) : LimitChangeService {
    override fun requestLimitRuleChange(command: RequestLimitRuleChangeCommand): LimitChangeRequestResult =
        requestWorkflow.execute(command)

    override fun approveLimitRuleChange(command: ApproveLimitRuleChangeCommand): LimitChangeRequestResult =
        approveWorkflow.execute(command)

    override fun rejectLimitRuleChange(command: RejectLimitRuleChangeCommand): LimitChangeRequestResult =
        rejectWorkflow.execute(command)

    override fun cancelLimitRuleChange(command: CancelLimitRuleChangeCommand): LimitChangeRequestResult =
        cancelWorkflow.execute(command)

    override fun applyDueLimitChanges(command: ApplyDueLimitChangesCommand): ApplyDueChangesResult =
        deferredWorkflow("applyDueLimitChanges")

    private fun deferredWorkflow(name: String): Nothing =
        throw UnsupportedOperationException("$name is deferred until the limit-change workflow slice")
}
