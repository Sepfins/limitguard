package io.github.sepfins.limitguard.port.persistence

import io.github.sepfins.limitguard.domain.channel.Channel
import io.github.sepfins.limitguard.domain.common.ChannelId
import io.github.sepfins.limitguard.domain.common.TenantId
import kotlin.time.Instant

interface ChannelRepository {
    fun findActive(tenantId: TenantId, at: Instant): List<Channel>
    fun find(tenantId: TenantId, channelId: ChannelId): Channel?
    fun insert(tenantId: TenantId, channel: Channel): Channel
    fun update(tenantId: TenantId, channel: Channel): Channel
}