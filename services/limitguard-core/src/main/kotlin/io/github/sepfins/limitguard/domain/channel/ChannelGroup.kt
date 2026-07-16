package io.github.sepfins.limitguard.domain.channel

import io.github.sepfins.limitguard.domain.common.ChannelGroupId
import io.github.sepfins.limitguard.domain.common.Metadata
import io.github.sepfins.limitguard.domain.common.TenantId
import java.time.Instant

data class ChannelGroup(
    val id: ChannelGroupId,
    val tenantId: TenantId,
    val code: ChannelGroupCode,
    val name: ChannelGroupName,
    val metadata: Metadata = Metadata.EMPTY,
    val createdAt: Instant? = null
)
