package io.github.sepfins.limitguard.port.persistence

import io.github.sepfins.limitguard.domain.common.EntityIdentifierId
import io.github.sepfins.limitguard.domain.common.IdentifierSchemeId
import io.github.sepfins.limitguard.domain.common.TenantId
import io.github.sepfins.limitguard.domain.identifier.EntityIdentifier
import io.github.sepfins.limitguard.domain.identifier.EntityType
import io.github.sepfins.limitguard.domain.identifier.IdentifierValue
import java.util.UUID

interface EntityIdentifierRepository {
    fun findById(tenantId: TenantId, id: EntityIdentifierId): EntityIdentifier?
    fun findByEntity(tenantId: TenantId, entityType: EntityType, entityId: UUID): List<EntityIdentifier>
    fun findByValue(
        tenantId: TenantId,
        schemeId: IdentifierSchemeId,
        identifier: IdentifierValue,
    ): EntityIdentifier?
    fun insert(tenantId: TenantId, entityIdentifier: EntityIdentifier): EntityIdentifier
}
