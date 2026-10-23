package io.github.sepfins.limitguard.admin.web

import io.github.sepfins.limitguard.admin.application.AdminCatalogApplicationService
import io.github.sepfins.limitguard.admin.security.AdminIdentityResolver
import io.github.sepfins.limitguard.admin.web.dto.AddChannelGroupMemberRequest
import io.github.sepfins.limitguard.admin.web.dto.ChannelGroupMemberResponse
import io.github.sepfins.limitguard.admin.web.dto.ChannelGroupResponse
import io.github.sepfins.limitguard.admin.web.dto.ChannelResponse
import io.github.sepfins.limitguard.admin.web.dto.CreateChannelGroupRequest
import io.github.sepfins.limitguard.admin.web.dto.CreateChannelRequest
import io.github.sepfins.limitguard.admin.web.dto.PageResponse
import io.github.sepfins.limitguard.admin.web.dto.SetChannelActiveRequest
import io.github.sepfins.limitguard.admin.web.dto.toAdminResponse
import io.github.sepfins.limitguard.api.commands.AddChannelGroupMemberCommand
import io.github.sepfins.limitguard.api.commands.CreateChannelGroupCommand
import io.github.sepfins.limitguard.api.commands.RegisterChannelCommand
import io.github.sepfins.limitguard.api.commands.SetChannelActiveCommand
import io.github.sepfins.limitguard.api.queries.ListChannelGroupsQuery
import io.github.sepfins.limitguard.api.queries.ListChannelsQuery
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
@RequestMapping("/limitguard/admin/api/v1/tenants/{tenantId}")
class ChannelAdminController(
    private val applicationService: AdminCatalogApplicationService,
    private val identityResolver: AdminIdentityResolver,
) {
    @GetMapping("/channels")
    fun listChannels(
        @PathVariable tenantId: UUID,
        @RequestParam(required = false) search: String?,
        @RequestParam(required = false) active: Boolean?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "25") size: Int,
        authentication: Authentication,
    ): PageResponse<ChannelResponse> =
        applicationService.listChannels(
            identityResolver.resolve(authentication),
            ListChannelsQuery(tenantId = tenantId, search = search, active = active, page = page, size = size),
        ).toAdminResponse { it.toAdminResponse() }

    @PostMapping("/channels")
    fun registerChannel(
        @PathVariable tenantId: UUID,
        @Valid @RequestBody request: CreateChannelRequest,
        authentication: Authentication,
    ): ResponseEntity<ChannelResponse> {
        val result = applicationService.registerChannel(
            identityResolver.resolve(authentication),
            RegisterChannelCommand(tenantId = tenantId, code = request.code, name = request.name),
        )
        return ResponseEntity
            .created(URI.create("/limitguard/admin/api/v1/tenants/$tenantId/channels/${result.id}"))
            .body(result.toAdminResponse())
    }

    @PutMapping("/channels/{channelId}/active")
    fun setChannelActive(
        @PathVariable tenantId: UUID,
        @PathVariable channelId: UUID,
        @Valid @RequestBody request: SetChannelActiveRequest,
        authentication: Authentication,
    ): ChannelResponse =
        applicationService.setChannelActive(
            identityResolver.resolve(authentication),
            SetChannelActiveCommand(tenantId = tenantId, channelId = channelId, isActive = request.isActive!!),
        ).toAdminResponse()

    @GetMapping("/channel-groups")
    fun listChannelGroups(
        @PathVariable tenantId: UUID,
        @RequestParam(required = false) search: String?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "25") size: Int,
        authentication: Authentication,
    ): PageResponse<ChannelGroupResponse> =
        applicationService.listChannelGroups(
            identityResolver.resolve(authentication),
            ListChannelGroupsQuery(tenantId = tenantId, search = search, page = page, size = size),
        ).toAdminResponse { it.toAdminResponse() }

    @PostMapping("/channel-groups")
    fun createChannelGroup(
        @PathVariable tenantId: UUID,
        @Valid @RequestBody request: CreateChannelGroupRequest,
        authentication: Authentication,
    ): ResponseEntity<ChannelGroupResponse> {
        val result = applicationService.createChannelGroup(
            identityResolver.resolve(authentication),
            CreateChannelGroupCommand(tenantId = tenantId, code = request.code, name = request.name),
        )
        return ResponseEntity
            .created(URI.create("/limitguard/admin/api/v1/tenants/$tenantId/channel-groups/${result.id}"))
            .body(result.toAdminResponse())
    }

    @PostMapping("/channel-groups/{channelGroupId}/members")
    fun addChannelGroupMember(
        @PathVariable tenantId: UUID,
        @PathVariable channelGroupId: UUID,
        @Valid @RequestBody request: AddChannelGroupMemberRequest,
        authentication: Authentication,
    ): ResponseEntity<ChannelGroupMemberResponse> {
        val result = applicationService.addChannelGroupMember(
            identityResolver.resolve(authentication),
            AddChannelGroupMemberCommand(
                tenantId = tenantId,
                channelGroupId = channelGroupId,
                channelId = request.channelId!!,
            ),
        )
        return ResponseEntity
            .created(
                URI.create(
                    "/limitguard/admin/api/v1/tenants/$tenantId/channel-groups/$channelGroupId/members/${result.channelId}",
                ),
            )
            .body(result.toAdminResponse())
    }
}
