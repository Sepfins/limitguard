package io.github.sepfins.limitguard.domain.channel

@JvmInline
value class ChannelName private constructor(val value: String) {
    companion object {
        fun of(value: String): ChannelName {
            val normalized = value.trim()
            require(normalized.isNotBlank()) { "ChannelName cannot be blank" }
            require(normalized.length <= 255) { "ChannelName cannot be longer than 255 characters" }
            return ChannelName(normalized)
        }
    }
}
