package io.github.sepfins.limitguard.admin.web.dto

import io.github.sepfins.limitguard.api.FundingSourceCategory
import io.github.sepfins.limitguard.api.NoMatchingRuleBehavior
import io.github.sepfins.limitguard.api.ProductNodeType
import io.github.sepfins.limitguard.api.results.FundingSourceProductResult
import io.github.sepfins.limitguard.api.results.PageResult
import io.github.sepfins.limitguard.api.results.TenantResult
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.Instant
import java.util.UUID

data class MeResponse(
    val subject: String,
    val displayName: String,
    val email: String?,
    val roles: Set<String>,
    val tenantIds: Set<UUID>,
    val logoutUrl: String,
    val csrfParameterName: String,
    val csrfToken: String,
)

data class CreateTenantRequest(
    @field:NotBlank
    @field:Size(max = 255)
    val name: String,
    val noMatchingRuleBehavior: NoMatchingRuleBehavior = NoMatchingRuleBehavior.DENY,
)

data class TenantResponse(
    val id: UUID,
    val name: String,
    val noMatchingRuleBehavior: NoMatchingRuleBehavior,
    val createdAt: Instant,
)

data class CreateFundingSourceProductRequest(
    @field:NotBlank
    @field:Size(max = 100)
    val code: String,
    @field:NotBlank
    @field:Size(max = 255)
    val name: String,
    val category: FundingSourceCategory,
    val nodeType: ProductNodeType,
    val parentId: UUID? = null,
    val isLeaf: Boolean = false,
    val isActive: Boolean = true,
)

data class FundingSourceProductResponse(
    val id: UUID,
    val tenantId: UUID,
    val parentId: UUID?,
    val category: FundingSourceCategory,
    val nodeType: ProductNodeType,
    val code: String,
    val name: String,
    val isLeaf: Boolean,
    val isActive: Boolean,
    val createdAt: Instant,
)

data class PageResponse<T>(
    val items: List<T>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
    val totalPages: Int,
)

fun TenantResult.toAdminResponse(): TenantResponse = TenantResponse(
    id = id,
    name = name,
    noMatchingRuleBehavior = noMatchingRuleBehavior,
    createdAt = createdAt,
)

fun FundingSourceProductResult.toAdminResponse(): FundingSourceProductResponse =
    FundingSourceProductResponse(
        id = id,
        tenantId = tenantId,
        parentId = parentId,
        category = category,
        nodeType = nodeType,
        code = code,
        name = name,
        isLeaf = isLeaf,
        isActive = isActive,
        createdAt = createdAt,
    )

fun <T, R> PageResult<T>.toAdminResponse(mapper: (T) -> R): PageResponse<R> = PageResponse(
    items = items.map(mapper),
    page = page,
    size = size,
    totalElements = totalElements,
    totalPages = totalPages,
)
