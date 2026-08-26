package io.github.sepfins.limitguard.application.change

import io.github.sepfins.limitguard.api.errors.InfrastructureException
import io.github.sepfins.limitguard.domain.change.LimitChangePolicyEngine
import io.github.sepfins.limitguard.port.policy.PolicyEngine

internal class PolicyEngineRegistry(engines: Collection<PolicyEngine>) {
    private val enginesByType: Map<LimitChangePolicyEngine, PolicyEngine>

    init {
        val duplicateTypes = engines
            .groupBy { it.engine }
            .filterValues { it.size > 1 }
            .keys
        require(duplicateTypes.isEmpty()) {
            "multiple policy engines registered for: ${duplicateTypes.joinToString { it.name }}"
        }
        enginesByType = engines.associateBy { it.engine }
    }

    fun get(engine: LimitChangePolicyEngine): PolicyEngine =
        enginesByType[engine]
            ?: throw InfrastructureException("no policy engine registered for ${engine.name}")
}
