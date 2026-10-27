package io.github.sepfins.limitguard.admin.web

import io.github.sepfins.limitguard.admin.application.AdminCatalogApplicationService
import io.github.sepfins.limitguard.admin.security.AdminIdentityResolver
import io.github.sepfins.limitguard.admin.web.dto.CreateOperationTypeRequest
import io.github.sepfins.limitguard.admin.web.dto.MoveOperationTypeRequest
import io.github.sepfins.limitguard.admin.web.dto.OperationTypeResponse
import io.github.sepfins.limitguard.admin.web.dto.PageResponse
import io.github.sepfins.limitguard.admin.web.dto.toAdminResponse
import io.github.sepfins.limitguard.api.commands.MoveOperationTypeCommand
import io.github.sepfins.limitguard.api.commands.RegisterOperationTypeCommand
import io.github.sepfins.limitguard.api.queries.ListOperationTypesQuery
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
import java.net.URI
import java.util.UUID

@RestController
@RequestMapping("/limitguard/admin/api/v1/tenants/{tenantId}/operation-types")
class OperationTypeAdminController(
    private val applicationService: AdminCatalogApplicationService,
    private val identityResolver: AdminIdentityResolver,
) {
    @GetMapping
    fun listOperationTypes(
        @PathVariable tenantId: UUID,
        @RequestParam(required = false) search: String?,
        @RequestParam(required = false) leaf: Boolean?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "25") size: Int,
        authentication: Authentication,
    ): PageResponse<OperationTypeResponse> =
        applicationService.listOperationTypes(
            identityResolver.resolve(authentication),
            ListOperationTypesQuery(tenantId = tenantId, search = search, leaf = leaf, page = page, size = size),
        ).toAdminResponse { it.toAdminResponse() }

    @PostMapping
    fun registerOperationType(
        @PathVariable tenantId: UUID,
        @Valid @RequestBody request: CreateOperationTypeRequest,
        authentication: Authentication,
    ): ResponseEntity<OperationTypeResponse> {
        val result = applicationService.registerOperationType(
            identityResolver.resolve(authentication),
            RegisterOperationTypeCommand(
                tenantId = tenantId,
                code = request.code,
                name = request.name,
                parentId = request.parentId,
                isLeaf = request.isLeaf,
                isGlobal = request.isGlobal,
            ),
        )
        return ResponseEntity
            .created(URI.create("/limitguard/admin/api/v1/tenants/$tenantId/operation-types/${result.id}"))
            .body(result.toAdminResponse())
    }

    @PutMapping("/{operationTypeId}/parent")
    fun moveOperationType(
        @PathVariable tenantId: UUID,
        @PathVariable operationTypeId: UUID,
        @RequestBody request: MoveOperationTypeRequest,
        authentication: Authentication,
    ): OperationTypeResponse =
        applicationService.moveOperationType(
            identityResolver.resolve(authentication),
            MoveOperationTypeCommand(tenantId = tenantId, operationTypeId = operationTypeId, parentId = request.parentId),
        ).toAdminResponse()
}
