package io.github.sepfins.limitguard.domain.channel

import io.github.sepfins.limitguard.domain.common.ChannelId
import io.github.sepfins.limitguard.domain.common.Metadata
import io.github.sepfins.limitguard.domain.common.TenantId
import java.time.Instant

data class Channel(
    val id: ChannelId,
    val tenantId: TenantId,
    val code: ChannelCode,
    val name: ChannelName,
    val isActive: Boolean,
    val metadata: Metadata = Metadata.EMPTY,
    val createdAt: Instant? = null
)
