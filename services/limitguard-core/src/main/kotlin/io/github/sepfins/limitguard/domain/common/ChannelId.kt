package io.github.sepfins.limitguard.domain.common

import java.util.UUID

@JvmInline
value class ChannelId(val id: UUID) {
    companion object {
        fun of(id: String): ChannelId = ChannelId(UUID.fromString(id))
    }
}
