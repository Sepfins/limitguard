package io.github.sepfins.limitguard.domain.channel

@JvmInline
value class ChannelGroupName private constructor(val value: String) {
    companion object {
        fun of(value: String): ChannelGroupName {
            val normalized = value.trim()
            require(normalized.isNotBlank()) { "ChannelGroupName cannot be blank" }
            require(normalized.length <= 255) { "ChannelGroupName cannot be longer than 255 characters" }
            return ChannelGroupName(normalized)
        }
    }
}
