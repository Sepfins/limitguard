package io.github.sepfins.limitguard.admin.web

import io.github.sepfins.limitguard.admin.application.AdminCatalogApplicationService
import io.github.sepfins.limitguard.admin.security.AdminIdentityResolver
import io.github.sepfins.limitguard.admin.web.dto.CreateLimitConfigurationRequest
import io.github.sepfins.limitguard.admin.web.dto.LimitConfigurationResponse
import io.github.sepfins.limitguard.admin.web.dto.PageResponse
import io.github.sepfins.limitguard.admin.web.dto.SetLimitConfigurationActiveRequest
import io.github.sepfins.limitguard.admin.web.dto.UpdateLimitConfigurationRequest
import io.github.sepfins.limitguard.admin.web.dto.toAdminResponse
import io.github.sepfins.limitguard.api.LimitAmount
import io.github.sepfins.limitguard.api.commands.CreateLimitConfigurationCommand
import io.github.sepfins.limitguard.api.commands.SetLimitConfigurationActiveCommand
import io.github.sepfins.limitguard.api.commands.UpdateLimitConfigurationCommand
import io.github.sepfins.limitguard.api.errors.ValidationException
import io.github.sepfins.limitguard.api.queries.ListLimitConfigurationsQuery
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.net.URI
import java.util.UUID

@RestController
@RequestMapping("/limitguard/admin/api/v1/tenants/{tenantId}/limit-configurations")
class LimitConfigurationAdminController(
    private val applicationService: AdminCatalogApplicationService,
    private val identityResolver: AdminIdentityResolver,
) {
    @GetMapping
    fun listLimitConfigurations(
        @PathVariable tenantId: UUID,
        @RequestParam(required = false) search: String?,
        @RequestParam(required = false) active: Boolean?,
        @RequestParam(required = false) parentConfigurationId: UUID?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "25") size: Int,
        authentication: Authentication,
    ): PageResponse<LimitConfigurationResponse> =
        applicationService.listLimitConfigurations(
            identityResolver.resolve(authentication),
            ListLimitConfigurationsQuery(
                tenantId = tenantId,
                search = search,
                active = active,
                parentConfigurationId = parentConfigurationId,
                page = page,
                size = size,
            ),
        ).toAdminResponse { it.toAdminResponse() }

    @PostMapping
    fun createLimitConfiguration(
        @PathVariable tenantId: UUID,
        @Valid @RequestBody request: CreateLimitConfigurationRequest,
        authentication: Authentication,
    ): ResponseEntity<LimitConfigurationResponse> {
        val result = applicationService.createLimitConfiguration(
            identityResolver.resolve(authentication),
            CreateLimitConfigurationCommand(
                tenantId = tenantId,
                code = request.code,
                name = request.name,
                description = request.description,
                limitKind = request.limitKind!!,
                period = request.period,
                currency = request.currency,
                maxAssignableAmount = maxAssignable(request),
                defaultAmount = request.defaultAmount?.let(::BigDecimal),
                parentConfigurationId = request.parentConfigurationId,
                fundingScope = request.fundingScope,
                fundingSourceCategory = request.fundingSourceCategory,
                fundingSourceProductId = request.fundingSourceProductId,
                channelScope = request.channelScope,
                channelId = request.channelId,
                channelGroupId = request.channelGroupId,
                operationTypeId = request.operationTypeId,
                priority = request.priority,
                usageConsumptionMode = request.usageConsumptionMode,
                startsAt = request.startsAt,
                endsAt = request.endsAt,
            ),
        )
        return ResponseEntity
            .created(URI.create("/limitguard/admin/api/v1/tenants/$tenantId/limit-configurations/${result.id}"))
            .body(result.toAdminResponse())
    }

    @PutMapping("/{configurationId}")
    fun updateLimitConfiguration(
        @PathVariable tenantId: UUID,
        @PathVariable configurationId: UUID,
        @Valid @RequestBody request: UpdateLimitConfigurationRequest,
        authentication: Authentication,
    ): LimitConfigurationResponse =
        applicationService.updateLimitConfiguration(
            identityResolver.resolve(authentication),
            UpdateLimitConfigurationCommand(
                tenantId = tenantId,
                limitConfigurationId = configurationId,
                name = request.name,
                description = request.description,
                defaultAmount = request.defaultAmount?.let(::BigDecimal),
            ),
        ).toAdminResponse()

    @PutMapping("/{configurationId}/active")
    fun setLimitConfigurationActive(
        @PathVariable tenantId: UUID,
        @PathVariable configurationId: UUID,
        @Valid @RequestBody request: SetLimitConfigurationActiveRequest,
        authentication: Authentication,
    ): LimitConfigurationResponse =
        applicationService.setLimitConfigurationActive(
            identityResolver.resolve(authentication),
            SetLimitConfigurationActiveCommand(
                tenantId = tenantId,
                limitConfigurationId = configurationId,
                isActive = request.isActive!!,
            ),
        ).toAdminResponse()

    private fun maxAssignable(request: CreateLimitConfigurationRequest): LimitAmount = when {
        request.maxAssignableUnlimited && request.maxAssignableAmount != null ->
            throw ValidationException("An unlimited maximum assignable amount takes no amount.")
        request.maxAssignableUnlimited -> LimitAmount.Unlimited
        request.maxAssignableAmount != null -> LimitAmount.Bounded(BigDecimal(request.maxAssignableAmount))
        else -> throw ValidationException("Enter a maximum assignable amount or mark it unlimited.")
    }
}
