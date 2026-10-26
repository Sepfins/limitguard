package io.github.sepfins.limitguard.application.catalog

import io.github.sepfins.limitguard.api.TenantCatalogQueryService
import io.github.sepfins.limitguard.api.errors.NotFoundException
import io.github.sepfins.limitguard.api.errors.ValidationException
import io.github.sepfins.limitguard.api.queries.ListChannelGroupsQuery
import io.github.sepfins.limitguard.api.queries.ListChannelsQuery
import io.github.sepfins.limitguard.api.queries.ListFundingSourceProductsQuery
import io.github.sepfins.limitguard.api.queries.ListLimitConfigurationsQuery
import io.github.sepfins.limitguard.api.queries.ListOperationTypesQuery
import io.github.sepfins.limitguard.api.queries.ListTenantsQuery
import io.github.sepfins.limitguard.api.queries.GetTenantQuery
import io.github.sepfins.limitguard.api.queries.MAX_CATALOG_PAGE_SIZE
import io.github.sepfins.limitguard.api.queries.MAX_CATALOG_SEARCH_LENGTH
import io.github.sepfins.limitguard.api.queries.ResolveEntityIdentifierQuery
import io.github.sepfins.limitguard.api.results.ChannelGroupResult
import io.github.sepfins.limitguard.api.results.ChannelResult
import io.github.sepfins.limitguard.api.results.EntityIdentifierResult
import io.github.sepfins.limitguard.api.results.FundingSourceProductResult
import io.github.sepfins.limitguard.api.results.LimitConfigurationResult
import io.github.sepfins.limitguard.api.results.OperationTypeResult
import io.github.sepfins.limitguard.api.results.PageResult
import io.github.sepfins.limitguard.api.results.TenantResult
import io.github.sepfins.limitguard.domain.common.LimitConfigurationId
import io.github.sepfins.limitguard.domain.common.TenantId
import io.github.sepfins.limitguard.domain.identifier.EntityType
import io.github.sepfins.limitguard.domain.identifier.IdentifierValue
import io.github.sepfins.limitguard.domain.identifier.SchemeName
import io.github.sepfins.limitguard.port.persistence.ChannelGroupMemberRepository
import io.github.sepfins.limitguard.port.persistence.ChannelGroupRepository
import io.github.sepfins.limitguard.port.persistence.ChannelRepository
import io.github.sepfins.limitguard.port.persistence.EntityIdentifierRepository
import io.github.sepfins.limitguard.port.persistence.FundingSourceProductRepository
import io.github.sepfins.limitguard.port.persistence.IdentifierSchemeRepository
import io.github.sepfins.limitguard.port.persistence.LimitConfigurationRepository
import io.github.sepfins.limitguard.port.persistence.OperationTypeRepository
import io.github.sepfins.limitguard.port.persistence.TenantRepository
import io.github.sepfins.limitguard.port.transactions.TransactionRunner
import java.util.UUID

class DefaultTenantCatalogQueryService(
    private val tenantRepository: TenantRepository,
    private val identifierSchemeRepository: IdentifierSchemeRepository,
    private val entityIdentifierRepository: EntityIdentifierRepository,
    private val fundingSourceProductRepository: FundingSourceProductRepository,
    private val channelRepository: ChannelRepository,
    private val channelGroupRepository: ChannelGroupRepository,
    private val channelGroupMemberRepository: ChannelGroupMemberRepository,
    private val operationTypeRepository: OperationTypeRepository,
    private val limitConfigurationRepository: LimitConfigurationRepository,
    private val transactionRunner: TransactionRunner,
) : TenantCatalogQueryService {
    override fun getTenant(query: GetTenantQuery): TenantResult = mapValidation {
        val tenantId = TenantId.of(query.tenantId)
        transactionRunner.inTransaction {
            tenantRepository.findById(tenantId)?.toResult()
                ?: throw NotFoundException("tenant not found: ${tenantId.value}")
        }
    }

    override fun resolveEntityIdentifier(
        query: ResolveEntityIdentifierQuery,
    ): EntityIdentifierResult? = mapValidation {
        val tenantId = TenantId.of(query.tenantId)
        val entityType = EntityType.of(query.entityType)
        val schemeName = SchemeName.of(query.schemeName)
        val identifier = IdentifierValue.of(query.identifier)

        transactionRunner.inTransaction {
            if (!tenantRepository.exists(tenantId)) {
                throw NotFoundException("tenant not found: ${tenantId.value}")
            }
            val scheme = identifierSchemeRepository.findByName(tenantId, entityType, schemeName)
                ?: return@inTransaction null
            entityIdentifierRepository.findByValue(tenantId, scheme.id, identifier)
                ?.toResult(scheme)
        }
    }

    override fun listTenants(query: ListTenantsQuery): PageResult<TenantResult> = mapValidation {
        val page = pageCoordinates(query.page, query.size)
        val search = normalizeSearch(query.search)

        transactionRunner.inTransaction {
            val total = tenantRepository.count(search)
            PageResult(
                items = tenantRepository.findPage(search, page.offset, page.size).map { it.toResult() },
                page = page.number,
                size = page.size,
                totalElements = total,
                totalPages = totalPages(total, page.size),
            )
        }
    }

    override fun listFundingSourceProducts(
        query: ListFundingSourceProductsQuery,
    ): PageResult<FundingSourceProductResult> =
        tenantPage(query.tenantId, query.page, query.size, query.search) { tenantId, search, page ->
            val total = fundingSourceProductRepository.count(tenantId, search, query.active)
            val products = fundingSourceProductRepository.findPage(
                tenantId = tenantId,
                search = search,
                active = query.active,
                offset = page.offset,
                limit = page.size,
            )
            products.map { it.toResult() } to total
        }

    override fun listChannels(query: ListChannelsQuery): PageResult<ChannelResult> =
        tenantPage(query.tenantId, query.page, query.size, query.search) { tenantId, search, page ->
            val total = channelRepository.count(tenantId, search, query.active)
            val channels = channelRepository.findPage(tenantId, search, query.active, page.offset, page.size)
            channels.map { it.toResult() } to total
        }

    override fun listChannelGroups(query: ListChannelGroupsQuery): PageResult<ChannelGroupResult> =
        tenantPage(query.tenantId, query.page, query.size, query.search) { tenantId, search, page ->
            val total = channelGroupRepository.count(tenantId, search)
            val groups = channelGroupRepository.findPage(tenantId, search, page.offset, page.size)
            val members = channelGroupMemberRepository.findByGroups(tenantId, groups.map { it.id })
                .groupBy({ it.groupId }, { it.channelId.value })
            groups.map { it.toResult(members[it.id].orEmpty()) } to total
        }

    override fun listOperationTypes(query: ListOperationTypesQuery): PageResult<OperationTypeResult> =
        tenantPage(query.tenantId, query.page, query.size, query.search) { tenantId, search, page ->
            val total = operationTypeRepository.count(tenantId, search, query.leaf)
            val operations = operationTypeRepository.findPage(tenantId, search, query.leaf, page.offset, page.size)
            operations.map { it.toResult() } to total
        }

    override fun listLimitConfigurations(
        query: ListLimitConfigurationsQuery,
    ): PageResult<LimitConfigurationResult> =
        tenantPage(query.tenantId, query.page, query.size, query.search) { tenantId, search, page ->
            val parentId = query.parentConfigurationId?.let { LimitConfigurationId.of(it) }
            val total = limitConfigurationRepository.count(tenantId, search, query.active, parentId)
            val configurations = limitConfigurationRepository.findPage(
                tenantId = tenantId,
                search = search,
                active = query.active,
                parentId = parentId,
                offset = page.offset,
                limit = page.size,
            )
            configurations.map { it.toResult() } to total
        }

    private fun <T> tenantPage(
        rawTenantId: UUID,
        rawPage: Int,
        rawSize: Int,
        rawSearch: String?,
        load: (TenantId, String?, PageCoordinates) -> Pair<List<T>, Long>,
    ): PageResult<T> = mapValidation {
        val tenantId = TenantId.of(rawTenantId)
        val page = pageCoordinates(rawPage, rawSize)
        val search = normalizeSearch(rawSearch)

        transactionRunner.inTransaction {
            if (!tenantRepository.exists(tenantId)) {
                throw NotFoundException("tenant not found: ${tenantId.value}")
            }
            val (items, total) = load(tenantId, search, page)
            PageResult(
                items = items,
                page = page.number,
                size = page.size,
                totalElements = total,
                totalPages = totalPages(total, page.size),
            )
        }
    }

    private fun pageCoordinates(page: Int, size: Int): PageCoordinates {
        require(page >= 0) { "page must be zero or greater" }
        require(size in 1..MAX_CATALOG_PAGE_SIZE) {
            "size must be between 1 and $MAX_CATALOG_PAGE_SIZE"
        }
        require(page <= Int.MAX_VALUE / size) { "page and size exceed the supported offset range" }
        return PageCoordinates(page, size, page * size)
    }

    private fun normalizeSearch(search: String?): String? {
        val normalized = search?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        require(normalized.length <= MAX_CATALOG_SEARCH_LENGTH) {
            "search must not exceed $MAX_CATALOG_SEARCH_LENGTH characters"
        }
        return normalized
    }

    private fun totalPages(totalElements: Long, size: Int): Int {
        if (totalElements == 0L) return 0
        val pages = ((totalElements - 1L) / size) + 1L
        require(pages <= Int.MAX_VALUE) { "result set exceeds the supported page range" }
        return pages.toInt()
    }

    private inline fun <T> mapValidation(block: () -> T): T =
        try {
            block()
        } catch (exception: IllegalArgumentException) {
            throw ValidationException(exception.message ?: "invalid catalog query", cause = exception)
        }

    private data class PageCoordinates(
        val number: Int,
        val size: Int,
        val offset: Int,
    )
}
