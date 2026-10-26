package io.github.sepfins.limitguard.admin.web

import io.github.sepfins.limitguard.admin.application.AdminCatalogApplicationService
import io.github.sepfins.limitguard.admin.security.AdminIdentityResolver
import io.github.sepfins.limitguard.admin.web.dto.CreateLimitChangePolicyRequest
import io.github.sepfins.limitguard.admin.web.dto.LimitChangePolicyResponse
import io.github.sepfins.limitguard.admin.web.dto.PageResponse
import io.github.sepfins.limitguard.admin.web.dto.toAdminResponse
import io.github.sepfins.limitguard.api.LimitChangePolicyPhase
import io.github.sepfins.limitguard.api.commands.CreateLimitChangePolicyCommand
import io.github.sepfins.limitguard.api.queries.ListLimitChangePoliciesQuery
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

// Policies are create-only, so there are no update or deactivate endpoints.
@RestController
@RequestMapping("/limitguard/admin/api/v1/tenants/{tenantId}/limit-change-policies")
class LimitChangePolicyAdminController(
    private val applicationService: AdminCatalogApplicationService,
    private val identityResolver: AdminIdentityResolver,
) {
    @GetMapping
    fun listLimitChangePolicies(
        @PathVariable tenantId: UUID,
        @RequestParam(required = false) phase: LimitChangePolicyPhase?,
        @RequestParam(required = false) limitConfigurationId: UUID?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "25") size: Int,
        authentication: Authentication,
    ): PageResponse<LimitChangePolicyResponse> =
        applicationService.listLimitChangePolicies(
            identityResolver.resolve(authentication),
            ListLimitChangePoliciesQuery(
                tenantId = tenantId,
                phase = phase,
                limitConfigurationId = limitConfigurationId,
                page = page,
                size = size,
            ),
        ).toAdminResponse { it.toAdminResponse() }

    @PostMapping
    fun createLimitChangePolicy(
        @PathVariable tenantId: UUID,
        @Valid @RequestBody request: CreateLimitChangePolicyRequest,
        authentication: Authentication,
    ): ResponseEntity<LimitChangePolicyResponse> {
        val result = applicationService.createLimitChangePolicy(
            identityResolver.resolve(authentication),
            CreateLimitChangePolicyCommand(
                tenantId = tenantId,
                code = request.code,
                name = request.name,
                description = request.description,
                phase = request.phase,
                decision = request.decision!!,
                limitConfigurationId = request.limitConfigurationId,
                priority = request.priority,
                conditionExpression = request.conditionExpression,
                actionExpression = request.actionExpression,
                denialCode = request.denialCode,
                denialMessage = request.denialMessage,
                startsAt = request.startsAt,
                endsAt = request.endsAt,
            ),
        )
        return ResponseEntity
            .created(URI.create("/limitguard/admin/api/v1/tenants/$tenantId/limit-change-policies/${result.id}"))
            .body(result.toAdminResponse())
    }
}
