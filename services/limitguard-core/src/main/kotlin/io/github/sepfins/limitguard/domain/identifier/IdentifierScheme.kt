package io.github.sepfins.limitguard.domain.identifier

import io.github.sepfins.limitguard.domain.common.IdentifierSchemeId
import io.github.sepfins.limitguard.domain.common.TenantId
import java.time.Instant

// Tenant-defined naming/format scheme for identifiers of a given entity type.
data class IdentifierScheme(
    val id: IdentifierSchemeId,
    val tenantId: TenantId,
    val entityType: EntityType,
    val schemeName: SchemeName,
    val formatRegex: String? = null,
    val maxLength: Int? = null,
    val isPrimary: Boolean = false,
    val createdAt: Instant? = null
) {
    init {
        require(maxLength == null || maxLength > 0) { "maxLength must be positive when set" }
    }
}
