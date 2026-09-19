package io.github.sepfins.limitguard.admin.web

import io.github.sepfins.limitguard.admin.application.AdminCatalogApplicationService
import io.github.sepfins.limitguard.admin.security.AdminIdentityResolver
import io.github.sepfins.limitguard.admin.web.dto.CreateFundingSourceProductRequest
import io.github.sepfins.limitguard.admin.web.dto.FundingSourceProductResponse
import io.github.sepfins.limitguard.admin.web.dto.PageResponse
import io.github.sepfins.limitguard.admin.web.dto.toAdminResponse
import io.github.sepfins.limitguard.api.queries.ListFundingSourceProductsQuery
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.net.URI
import java.util.UUID

@RestController
@RequestMapping("/limitguard/admin/api/v1/tenants/{tenantId}/funding-source-products")
class FundingSourceProductAdminController(
    private val applicationService: AdminCatalogApplicationService,
    private val identityResolver: AdminIdentityResolver,
) {
    @GetMapping
    fun list(
        @PathVariable tenantId: UUID,
        @RequestParam(required = false) search: String?,
        @RequestParam(required = false) active: Boolean?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "25") size: Int,
        authentication: Authentication,
    ): PageResponse<FundingSourceProductResponse> =
        applicationService.listFundingSourceProducts(
            identityResolver.resolve(authentication),
            ListFundingSourceProductsQuery(
                tenantId = tenantId,
                search = search,
                active = active,
                page = page,
                size = size,
            ),
        ).toAdminResponse { it.toAdminResponse() }

    @PostMapping
    fun create(
        @PathVariable tenantId: UUID,
        @Valid @RequestBody request: CreateFundingSourceProductRequest,
        authentication: Authentication,
    ): ResponseEntity<FundingSourceProductResponse> {
        val result = applicationService.createFundingSourceProduct(
            identity = identityResolver.resolve(authentication),
            tenantId = tenantId,
            code = request.code,
            name = request.name,
            category = request.category,
            nodeType = request.nodeType,
            parentId = request.parentId,
            isLeaf = request.isLeaf,
            isActive = request.isActive,
        )
        return ResponseEntity
            .created(
                URI.create(
                    "/limitguard/admin/api/v1/tenants/$tenantId/funding-source-products/${result.id}",
                ),
            )
            .body(result.toAdminResponse())
    }
}
