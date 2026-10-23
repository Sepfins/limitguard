package io.github.sepfins.limitguard.port.persistence

import io.github.sepfins.limitguard.domain.channel.Channel
import io.github.sepfins.limitguard.domain.channel.ChannelCode
import io.github.sepfins.limitguard.domain.common.ChannelId
import io.github.sepfins.limitguard.domain.common.TenantId
import java.time.Instant

interface ChannelRepository {
    fun findActive(tenantId: TenantId): List<Channel>
    fun findById(tenantId: TenantId, channelId: ChannelId): Channel?
    fun findByIdForUpdate(tenantId: TenantId, channelId: ChannelId): Channel?
    fun findByCode(tenantId: TenantId, code: ChannelCode): Channel?
    fun findPage(tenantId: TenantId, search: String?, active: Boolean?, offset: Int, limit: Int): List<Channel>
    fun count(tenantId: TenantId, search: String?, active: Boolean?): Long
    fun insert(tenantId: TenantId, channel: Channel): Channel
    fun update(tenantId: TenantId, channel: Channel): Channel
}
