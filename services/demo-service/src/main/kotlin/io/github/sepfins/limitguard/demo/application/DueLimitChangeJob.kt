package io.github.sepfins.limitguard.demo.application

import io.github.sepfins.limitguard.api.Limitguard
import io.github.sepfins.limitguard.api.commands.ApplyDueLimitChangesCommand
import io.github.sepfins.limitguard.demo.persistence.DemoBankRepository
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service

/**
 * The bank's due pass. LimitGuard schedules a delayed change but never runs jobs itself, so
 * the host applies changes whose effective time has come.
 */
@Service
class DueLimitChangeJob(
    private val limitguard: Limitguard,
    private val scenarioService: DemoScenarioService,
    private val repository: DemoBankRepository,
    private val eventStream: DemoEventStream,
) {
    @Scheduled(fixedDelayString = "\${limitguard.demo.due-pass-interval:15s}")
    fun applyDueChanges() {
        val scenario = scenarioService.currentOrNull() ?: return
        val result = limitguard.limitChangeService.applyDueLimitChanges(
            ApplyDueLimitChangesCommand(tenantId = requireNotNull(scenario.tenantId)),
        )
        (result.appliedChangeRequestIds + result.failedChangeRequestIds).forEach { changeRequestId ->
            val setting = repository.findLimitSettingByPendingChange(scenario.id, changeRequestId) ?: return@forEach
            repository.updatePendingChange(setting.id, null, null, null)
            eventStream.publish("limits.updated", "limit", setting.id.toString())
        }
    }
}
