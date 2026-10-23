package io.github.sepfins.limitguard.port.persistence

import io.github.sepfins.limitguard.domain.channel.ChannelGroup
import io.github.sepfins.limitguard.domain.channel.ChannelGroupCode
import io.github.sepfins.limitguard.domain.common.ChannelGroupId
import io.github.sepfins.limitguard.domain.common.TenantId

interface ChannelGroupRepository {
    fun findById(tenantId: TenantId, id: ChannelGroupId): ChannelGroup?
    fun findByCode(tenantId: TenantId, code: ChannelGroupCode): ChannelGroup?
    fun findPage(tenantId: TenantId, search: String?, offset: Int, limit: Int): List<ChannelGroup>
    fun count(tenantId: TenantId, search: String?): Long
    fun insert(tenantId: TenantId, channelGroup: ChannelGroup): ChannelGroup
    fun update(tenantId: TenantId, channelGroup: ChannelGroup): ChannelGroup
}
