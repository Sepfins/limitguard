package io.github.sepfins.limitguard.api

import io.github.sepfins.limitguard.api.queries.ResolveEntityIdentifierQuery
import io.github.sepfins.limitguard.api.results.EntityIdentifierResult

interface TenantCatalogQueryService {
    fun resolveEntityIdentifier(query: ResolveEntityIdentifierQuery): EntityIdentifierResult?
}
