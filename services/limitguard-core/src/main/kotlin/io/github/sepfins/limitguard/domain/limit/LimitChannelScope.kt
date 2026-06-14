package io.github.sepfins.limitguard.domain.limit

import io.github.sepfins.limitguard.domain.common.ChannelGroupId
import io.github.sepfins.limitguard.domain.common.ChannelId

sealed interface LimitChannelScope {
    data object Global : LimitChannelScope

    data class ChannelGroupSpecific(val channelGroupId: ChannelGroupId) : LimitChannelScope

    data class ChannelSpecific(val channelId: ChannelId) : LimitChannelScope
}