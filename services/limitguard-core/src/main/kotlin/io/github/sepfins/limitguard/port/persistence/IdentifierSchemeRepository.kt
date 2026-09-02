package io.github.sepfins.limitguard.port.persistence

import io.github.sepfins.limitguard.domain.common.IdentifierSchemeId
import io.github.sepfins.limitguard.domain.common.TenantId
import io.github.sepfins.limitguard.domain.identifier.EntityType
import io.github.sepfins.limitguard.domain.identifier.IdentifierScheme
import io.github.sepfins.limitguard.domain.identifier.SchemeName

interface IdentifierSchemeRepository {
    fun findById(tenantId: TenantId, id: IdentifierSchemeId): IdentifierScheme?
    fun findByName(tenantId: TenantId, entityType: EntityType, schemeName: SchemeName): IdentifierScheme?
    fun findPrimary(tenantId: TenantId, entityType: EntityType): IdentifierScheme?
    fun insert(tenantId: TenantId, scheme: IdentifierScheme): IdentifierScheme
    fun update(tenantId: TenantId, scheme: IdentifierScheme): IdentifierScheme
}
