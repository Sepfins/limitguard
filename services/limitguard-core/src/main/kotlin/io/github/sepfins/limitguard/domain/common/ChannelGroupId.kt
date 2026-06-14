package io.github.sepfins.limitguard.domain.common

import java.util.UUID

@JvmInline
value class ChannelGroupId(val id: UUID) {
    companion object {
        fun of(id: String) : ChannelGroupId = ChannelGroupId(UUID.fromString(id))
    }
}
