package io.github.sepfins.limitguard.api

import io.github.sepfins.limitguard.api.queries.ListChannelGroupsQuery
import io.github.sepfins.limitguard.api.queries.ListChannelsQuery
import io.github.sepfins.limitguard.api.queries.ListFundingSourceProductsQuery
import io.github.sepfins.limitguard.api.queries.ListTenantsQuery
import io.github.sepfins.limitguard.api.queries.GetTenantQuery
import io.github.sepfins.limitguard.api.queries.ResolveEntityIdentifierQuery
import io.github.sepfins.limitguard.api.results.ChannelGroupResult
import io.github.sepfins.limitguard.api.results.ChannelResult
import io.github.sepfins.limitguard.api.results.EntityIdentifierResult
import io.github.sepfins.limitguard.api.results.FundingSourceProductResult
import io.github.sepfins.limitguard.api.results.PageResult
import io.github.sepfins.limitguard.api.results.TenantResult

interface TenantCatalogQueryService {
    fun getTenant(query: GetTenantQuery): TenantResult

    fun resolveEntityIdentifier(query: ResolveEntityIdentifierQuery): EntityIdentifierResult?

    fun listTenants(query: ListTenantsQuery): PageResult<TenantResult>

    fun listFundingSourceProducts(
        query: ListFundingSourceProductsQuery,
    ): PageResult<FundingSourceProductResult>

    fun listChannels(query: ListChannelsQuery): PageResult<ChannelResult>

    /** Each group carries its current member channel ids. */
    fun listChannelGroups(query: ListChannelGroupsQuery): PageResult<ChannelGroupResult>
}
