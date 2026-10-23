package io.github.sepfins.limitguard.port.persistence

import io.github.sepfins.limitguard.domain.common.ChannelGroupId
import io.github.sepfins.limitguard.domain.common.ChannelId
import io.github.sepfins.limitguard.domain.common.TenantId
import io.github.sepfins.limitguard.domain.channel.ChannelGroupMember

interface ChannelGroupMemberRepository {
    fun findByGroup(tenantId: TenantId, groupId: ChannelGroupId): List<ChannelGroupMember>
    fun findByGroups(tenantId: TenantId, groupIds: Collection<ChannelGroupId>): List<ChannelGroupMember>

    fun findGroupIdsContaining(
        tenantId: TenantId,
        channelId: ChannelId,
    ): Set<ChannelGroupId>

    fun insert(tenantId: TenantId, member: ChannelGroupMember): ChannelGroupMember
}
