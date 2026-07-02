package io.github.sepfins.limitguard.domain.common

import java.util.UUID

@JvmInline
value class ChannelId(val value: UUID) {
    companion object {
        fun of(value: UUID): ChannelId = ChannelId(value)

        fun of(value: String): ChannelId = of(UUID.fromString(value))
    }
}
