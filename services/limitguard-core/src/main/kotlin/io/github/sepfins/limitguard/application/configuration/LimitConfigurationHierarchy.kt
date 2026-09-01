package io.github.sepfins.limitguard.application.configuration

import io.github.sepfins.limitguard.api.errors.ConflictException
import io.github.sepfins.limitguard.api.errors.NotFoundException
import io.github.sepfins.limitguard.domain.common.LimitConfigurationId
import io.github.sepfins.limitguard.domain.common.TenantId
import io.github.sepfins.limitguard.domain.limit.LimitConfiguration
import io.github.sepfins.limitguard.port.persistence.LimitConfigurationRepository

/** Resolves the selected configuration followed by each parent up to the root. */
internal fun LimitConfigurationRepository.resolveHierarchy(
    tenantId: TenantId,
    configurationId: LimitConfigurationId,
): List<LimitConfiguration> {
    val hierarchy = mutableListOf<LimitConfiguration>()
    val visited = mutableSetOf<LimitConfigurationId>()
    var nextId: LimitConfigurationId? = configurationId

    while (nextId != null) {
        if (!visited.add(nextId)) {
            throw ConflictException("limit configuration hierarchy contains a cycle: ${nextId.value}")
        }
        val configuration = findById(tenantId, nextId)
            ?: throw NotFoundException("limit configuration not found: ${nextId.value}")
        hierarchy += configuration
        nextId = configuration.parentConfigurationId
    }

    return hierarchy
}
