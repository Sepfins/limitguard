package io.github.sepfins.limitguard.application.configuration

import io.github.sepfins.limitguard.application.operation.OperationTypeAncestry
import io.github.sepfins.limitguard.domain.common.OperationTypeId
import io.github.sepfins.limitguard.domain.common.TenantId
import io.github.sepfins.limitguard.domain.limit.LimitConfiguration
import io.github.sepfins.limitguard.domain.limit.LimitOperationScope
import io.github.sepfins.limitguard.domain.common.LimitConfigurationId
import io.github.sepfins.limitguard.port.persistence.LimitConfigurationRepository
import io.github.sepfins.limitguard.port.persistence.OperationTypeRepository

internal class OperationConfigurationEnvelopes(
    private val configurations: LimitConfigurationRepository,
    private val operations: OperationTypeRepository,
) {
    fun validateOperationGrowth(tenantId: TenantId) {
        val all = configurations.findAll(tenantId)
        val byId = all.associateBy { it.id }
        val paths = mutableMapOf<OperationTypeId, List<OperationTypeId>>()
        all.forEach { configuration -> validateHierarchy(configuration, paths) { byId[it] } }
    }

    fun validateHierarchy(configuration: LimitConfiguration) {
        validateHierarchy(configuration, mutableMapOf()) { configurations.findById(configuration.tenantId, it) }
    }

    private fun validateHierarchy(
        configuration: LimitConfiguration,
        paths: MutableMap<OperationTypeId, List<OperationTypeId>>,
        find: (LimitConfigurationId) -> LimitConfiguration?,
    ) {
        val hierarchy = listOf(configuration) + configuration.parentConfigurationId
            ?.let { resolveHierarchy(it, find) }.orEmpty()
        hierarchy.forEach {
            val scope = it.operationScope
            if (scope is LimitOperationScope.OperationTypeSpecific) {
                paths.getOrPut(scope.operationTypeId) {
                    OperationTypeAncestry(operations).resolve(configuration.tenantId, scope.operationTypeId).map { node -> node.id }
                }
            }
        }
        hierarchy.zipWithNext().forEach { (child, parent) ->
            child.validateAgainstParent(parent)
            // A subtree includes its selected node, so its ancestry determines containment.
            val contained = when (val childScope = child.operationScope) {
                LimitOperationScope.AllOperations -> parent.operationScope == LimitOperationScope.AllOperations
                is LimitOperationScope.OperationTypeSpecific -> parent.operationScope.includes(paths.getValue(childScope.operationTypeId))
            }
            require(contained) {
                "configuration ${child.id.value} operation coverage exceeds parent ${parent.id.value}; correct the operation envelope explicitly"
            }
        }
    }
}
