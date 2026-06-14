package io.github.sepfins.limitguard.domain.channel

import io.github.sepfins.limitguard.domain.common.ChannelGroupId
import io.github.sepfins.limitguard.domain.common.ChannelId
import io.github.sepfins.limitguard.domain.common.TenantId
import kotlin.time.Instant

data class ChannelGroupMember(
    val tenantId: TenantId,
    val groupId: ChannelGroupId,
    val channelId: ChannelId,
    val createdAt: Instant? = null
)
