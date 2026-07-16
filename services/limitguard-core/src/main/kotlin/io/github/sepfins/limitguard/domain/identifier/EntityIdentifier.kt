package io.github.sepfins.limitguard.domain.identifier

import io.github.sepfins.limitguard.domain.common.EntityIdentifierId
import io.github.sepfins.limitguard.domain.common.IdentifierSchemeId
import java.util.UUID
import java.time.Instant

// A concrete external identifier for some entity, validated by its scheme.
data class EntityIdentifier(
    val id: EntityIdentifierId,
    val entityType: EntityType,
    // Polymorphic reference to the owning entity (a party, a funding source, ...),
    // qualified by entityType. No single id type fits, so it stays a raw UUID.
    val entityId: UUID,
    val schemeId: IdentifierSchemeId,
    val identifier: IdentifierValue,
    val createdAt: Instant? = null
)
