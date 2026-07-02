package io.github.sepfins.limitguard.domain.common

import java.util.UUID

@JvmInline
value class ChannelGroupId(val value: UUID) {
    companion object {
        fun of(value: UUID): ChannelGroupId = ChannelGroupId(value)

        fun of(value: String): ChannelGroupId = of(UUID.fromString(value))
    }
}
