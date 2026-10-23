package io.github.sepfins.limitguard.admin.application

import io.github.sepfins.limitguard.admin.security.AdminAuthorizer
import io.github.sepfins.limitguard.admin.security.AdminIdentity
import io.github.sepfins.limitguard.api.Limitguard
import io.github.sepfins.limitguard.api.NoMatchingRuleBehavior
import io.github.sepfins.limitguard.api.FundingSourceCategory
import io.github.sepfins.limitguard.api.ProductNodeType
import io.github.sepfins.limitguard.api.commands.AddChannelGroupMemberCommand
import io.github.sepfins.limitguard.api.commands.CreateChannelGroupCommand
import io.github.sepfins.limitguard.api.commands.RegisterChannelCommand
import io.github.sepfins.limitguard.api.commands.RegisterFundingSourceProductCommand
import io.github.sepfins.limitguard.api.commands.RegisterTenantCommand
import io.github.sepfins.limitguard.api.commands.SetChannelActiveCommand
import io.github.sepfins.limitguard.api.errors.NotFoundException
import io.github.sepfins.limitguard.api.errors.ValidationException
import io.github.sepfins.limitguard.api.queries.GetTenantQuery
import io.github.sepfins.limitguard.api.queries.ListChannelGroupsQuery
import io.github.sepfins.limitguard.api.queries.ListChannelsQuery
import io.github.sepfins.limitguard.api.queries.ListFundingSourceProductsQuery
import io.github.sepfins.limitguard.api.queries.ListTenantsQuery
import io.github.sepfins.limitguard.api.queries.MAX_CATALOG_PAGE_SIZE
import io.github.sepfins.limitguard.api.queries.MAX_CATALOG_SEARCH_LENGTH
import io.github.sepfins.limitguard.api.results.ChannelGroupMemberResult
import io.github.sepfins.limitguard.api.results.ChannelGroupResult
import io.github.sepfins.limitguard.api.results.ChannelResult
import io.github.sepfins.limitguard.api.results.FundingSourceProductResult
import io.github.sepfins.limitguard.api.results.PageResult
import io.github.sepfins.limitguard.api.results.TenantResult
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class AdminCatalogApplicationService(
    private val limitguard: Limitguard,
    private val authorizer: AdminAuthorizer,
) {
    fun listTenants(
        identity: AdminIdentity,
        query: ListTenantsQuery,
    ): PageResult<TenantResult> {
        authorizer.requireReader(identity)
        if (identity.isPlatformAdmin) {
            return limitguard.tenantCatalogQueryService.listTenants(query)
        }

        validatePage(query.page, query.size)
        val search = normalizeSearch(query.search)
        val tenants = identity.tenantIds
            .mapNotNull { tenantId ->
                try {
                    limitguard.tenantCatalogQueryService.getTenant(GetTenantQuery(tenantId))
                } catch (_: NotFoundException) {
                    null
                }
            }
            .filter { tenant -> search == null || tenant.name.contains(search, ignoreCase = true) }
            .sortedWith(compareBy<TenantResult>({ it.name }, { it.id }))
        val offset = query.page * query.size
        val items = tenants.drop(offset).take(query.size)
        return PageResult(
            items = items,
            page = query.page,
            size = query.size,
            totalElements = tenants.size.toLong(),
            totalPages = totalPages(tenants.size.toLong(), query.size),
        )
    }

    fun createTenant(
        identity: AdminIdentity,
        name: String,
        noMatchingRuleBehavior: NoMatchingRuleBehavior,
    ): TenantResult {
        authorizer.requireTenantCreation(identity)
        return limitguard.tenantCatalogService.registerTenant(
            RegisterTenantCommand(
                name = name,
                config = emptyMap(),
                noMatchingRuleBehavior = noMatchingRuleBehavior,
            ),
        )
    }

    fun listFundingSourceProducts(
        identity: AdminIdentity,
        query: ListFundingSourceProductsQuery,
    ): PageResult<FundingSourceProductResult> {
        authorizer.requireTenantRead(identity, query.tenantId)
        return limitguard.tenantCatalogQueryService.listFundingSourceProducts(query)
    }

    fun createFundingSourceProduct(
        identity: AdminIdentity,
        tenantId: UUID,
        code: String,
        name: String,
        category: FundingSourceCategory,
        nodeType: ProductNodeType,
        parentId: UUID?,
        isLeaf: Boolean,
        isActive: Boolean,
    ): FundingSourceProductResult {
        authorizer.requireTenantMutation(identity, tenantId)
        return limitguard.tenantCatalogService.registerFundingSourceProduct(
            RegisterFundingSourceProductCommand(
                tenantId = tenantId,
                code = code,
                name = name,
                category = category,
                nodeType = nodeType,
                parentId = parentId,
                isLeaf = isLeaf,
                isActive = isActive,
                metadata = emptyMap(),
            ),
        )
    }

    fun listChannels(identity: AdminIdentity, query: ListChannelsQuery): PageResult<ChannelResult> {
        authorizer.requireTenantRead(identity, query.tenantId)
        return limitguard.tenantCatalogQueryService.listChannels(query)
    }

    fun registerChannel(identity: AdminIdentity, command: RegisterChannelCommand): ChannelResult {
        authorizer.requireTenantMutation(identity, command.tenantId)
        return limitguard.tenantCatalogService.registerChannel(command)
    }

    fun setChannelActive(identity: AdminIdentity, command: SetChannelActiveCommand): ChannelResult {
        authorizer.requireTenantMutation(identity, command.tenantId)
        return limitguard.tenantCatalogService.setChannelActive(command)
    }

    fun listChannelGroups(identity: AdminIdentity, query: ListChannelGroupsQuery): PageResult<ChannelGroupResult> {
        authorizer.requireTenantRead(identity, query.tenantId)
        return limitguard.tenantCatalogQueryService.listChannelGroups(query)
    }

    fun createChannelGroup(identity: AdminIdentity, command: CreateChannelGroupCommand): ChannelGroupResult {
        authorizer.requireTenantMutation(identity, command.tenantId)
        return limitguard.tenantCatalogService.createChannelGroup(command)
    }

    fun addChannelGroupMember(
        identity: AdminIdentity,
        command: AddChannelGroupMemberCommand,
    ): ChannelGroupMemberResult {
        authorizer.requireTenantMutation(identity, command.tenantId)
        return limitguard.tenantCatalogService.addChannelGroupMember(command)
    }

    private fun validatePage(page: Int, size: Int) {
        if (page < 0) throw ValidationException("page must be zero or greater")
        if (size !in 1..MAX_CATALOG_PAGE_SIZE) {
            throw ValidationException("size must be between 1 and $MAX_CATALOG_PAGE_SIZE")
        }
        if (page > Int.MAX_VALUE / size) {
            throw ValidationException("page and size exceed the supported offset range")
        }
    }

    private fun normalizeSearch(search: String?): String? {
        val normalized = search?.trim()?.takeIf(String::isNotEmpty) ?: return null
        if (normalized.length > MAX_CATALOG_SEARCH_LENGTH) {
            throw ValidationException("search must not exceed $MAX_CATALOG_SEARCH_LENGTH characters")
        }
        return normalized
    }

    private fun totalPages(totalElements: Long, size: Int): Int =
        if (totalElements == 0L) 0 else (((totalElements - 1L) / size) + 1L).toInt()
}
