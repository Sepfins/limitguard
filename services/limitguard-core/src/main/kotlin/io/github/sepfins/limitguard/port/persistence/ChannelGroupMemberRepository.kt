package io.github.sepfins.limitguard.port.persistence

import io.github.sepfins.limitguard.domain.common.ChannelGroupId
import io.github.sepfins.limitguard.domain.common.ChannelId
import io.github.sepfins.limitguard.domain.common.TenantId

interface ChannelGroupMemberRepository {
    fun findGroupIdsContaining(
        tenantId: TenantId,
        channelId: ChannelId,
    ): Set<ChannelGroupId>
}
