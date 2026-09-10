package io.github.sepfins.limitguard.api.queries

import java.util.UUID

data class ResolveEntityIdentifierQuery(
    val tenantId: UUID,
    val entityType: String,
    val schemeName: String,
    val identifier: String,
)
