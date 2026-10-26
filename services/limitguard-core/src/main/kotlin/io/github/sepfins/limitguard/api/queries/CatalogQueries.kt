package io.github.sepfins.limitguard.api.queries

import java.util.UUID

data class GetTenantQuery(
    val tenantId: UUID,
)

data class ResolveEntityIdentifierQuery(
    val tenantId: UUID,
    val entityType: String,
    val schemeName: String,
    val identifier: String,
)

data class ListTenantsQuery(
    val search: String? = null,
    val page: Int = 0,
    val size: Int = DEFAULT_CATALOG_PAGE_SIZE,
)

data class ListFundingSourceProductsQuery(
    val tenantId: UUID,
    val search: String? = null,
    val active: Boolean? = null,
    val page: Int = 0,
    val size: Int = DEFAULT_CATALOG_PAGE_SIZE,
)

data class ListChannelsQuery(
    val tenantId: UUID,
    val search: String? = null,
    val active: Boolean? = null,
    val page: Int = 0,
    val size: Int = DEFAULT_CATALOG_PAGE_SIZE,
)

data class ListChannelGroupsQuery(
    val tenantId: UUID,
    val search: String? = null,
    val page: Int = 0,
    val size: Int = DEFAULT_CATALOG_PAGE_SIZE,
)

data class ListOperationTypesQuery(
    val tenantId: UUID,
    val search: String? = null,
    val leaf: Boolean? = null,
    val page: Int = 0,
    val size: Int = DEFAULT_CATALOG_PAGE_SIZE,
)

data class ListLimitConfigurationsQuery(
    val tenantId: UUID,
    val search: String? = null,
    val active: Boolean? = null,
    val parentConfigurationId: UUID? = null,
    val page: Int = 0,
    val size: Int = DEFAULT_CATALOG_PAGE_SIZE,
)

const val DEFAULT_CATALOG_PAGE_SIZE: Int = 25
const val MAX_CATALOG_PAGE_SIZE: Int = 100
const val MAX_CATALOG_SEARCH_LENGTH: Int = 100
