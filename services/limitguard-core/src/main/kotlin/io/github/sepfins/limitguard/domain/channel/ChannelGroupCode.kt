package io.github.sepfins.limitguard.domain.channel

@JvmInline
value class ChannelGroupCode private constructor(val value: String) {
    companion object {
        fun of(value: String): ChannelGroupCode {
            val normalized = value.trim().uppercase()
            require(normalized.isNotBlank()) { "ChannelGroupCode cannot be blank" }
            require(normalized.length <= 100) { "ChannelGroupCode cannot be longer than 100 characters" }
            return ChannelGroupCode(normalized)
        }
    }
}
