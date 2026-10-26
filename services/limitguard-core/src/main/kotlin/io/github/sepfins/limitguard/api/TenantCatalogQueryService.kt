package io.github.sepfins.limitguard.api

import io.github.sepfins.limitguard.api.queries.ListChannelGroupsQuery
import io.github.sepfins.limitguard.api.queries.ListChannelsQuery
import io.github.sepfins.limitguard.api.queries.ListFundingSourceProductsQuery
import io.github.sepfins.limitguard.api.queries.ListLimitChangePoliciesQuery
import io.github.sepfins.limitguard.api.queries.ListLimitConfigurationsQuery
import io.github.sepfins.limitguard.api.queries.ListOperationTypesQuery
import io.github.sepfins.limitguard.api.queries.ListTenantsQuery
import io.github.sepfins.limitguard.api.queries.GetTenantQuery
import io.github.sepfins.limitguard.api.queries.ResolveEntityIdentifierQuery
import io.github.sepfins.limitguard.api.results.ChannelGroupResult
import io.github.sepfins.limitguard.api.results.ChannelResult
import io.github.sepfins.limitguard.api.results.EntityIdentifierResult
import io.github.sepfins.limitguard.api.results.FundingSourceProductResult
import io.github.sepfins.limitguard.api.results.LimitChangePolicyResult
import io.github.sepfins.limitguard.api.results.LimitConfigurationResult
import io.github.sepfins.limitguard.api.results.OperationTypeResult
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

    /**
     * Lists the operation types visible to the tenant, global ones included, in tree order:
     * each parent before its children, siblings by code.
     */
    fun listOperationTypes(query: ListOperationTypesQuery): PageResult<OperationTypeResult>

    /** Includes disabled configurations and every effective window, ordered by code. */
    fun listLimitConfigurations(query: ListLimitConfigurationsQuery): PageResult<LimitConfigurationResult>

    /**
     * Includes inactive policies and every effective window, in decision order: priority, then code.
     * The configuration filter matches the bound configuration exactly, not its children.
     */
    fun listLimitChangePolicies(query: ListLimitChangePoliciesQuery): PageResult<LimitChangePolicyResult>
}
