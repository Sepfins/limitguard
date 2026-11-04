package io.github.sepfins.limitguard.admin.web

import io.github.sepfins.limitguard.admin.application.AdminCatalogApplicationService
import io.github.sepfins.limitguard.admin.security.AdminIdentityResolver
import io.github.sepfins.limitguard.admin.web.dto.CreateTenantRequest
import io.github.sepfins.limitguard.admin.web.dto.PageResponse
import io.github.sepfins.limitguard.admin.web.dto.TenantResponse
import io.github.sepfins.limitguard.admin.web.dto.toAdminResponse
import io.github.sepfins.limitguard.api.queries.ListTenantsQuery
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.net.URI

@RestController
@RequestMapping("/limitguard/admin/api/v1/tenants")
class TenantAdminController(
    private val applicationService: AdminCatalogApplicationService,
    private val identityResolver: AdminIdentityResolver,
) {
    @GetMapping
    fun list(
        @RequestParam(required = false) search: String?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "25") size: Int,
        authentication: Authentication,
    ): PageResponse<TenantResponse> = applicationService.listTenants(
        identityResolver.resolve(authentication),
        ListTenantsQuery(search = search, page = page, size = size),
    ).toAdminResponse { it.toAdminResponse() }

    @PostMapping
    fun create(
        @Valid @RequestBody request: CreateTenantRequest,
        authentication: Authentication,
    ): ResponseEntity<TenantResponse> {
        val result = applicationService.createTenant(
            identity = identityResolver.resolve(authentication),
            name = request.name,
            timeZone = request.timeZone,
            noMatchingRuleBehavior = request.noMatchingRuleBehavior,
        )
        return ResponseEntity
            .created(URI.create("/limitguard/admin/api/v1/tenants/${result.id}"))
            .body(result.toAdminResponse())
    }
}
