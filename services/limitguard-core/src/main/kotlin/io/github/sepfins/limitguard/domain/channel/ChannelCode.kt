package io.github.sepfins.limitguard.domain.channel

@JvmInline
value class ChannelCode private constructor(val value: String) {
    companion object {
        fun of(value: String) : ChannelCode {
            val normalized = value.trim().uppercase()
            require(normalized.isNotBlank()) { "ChannelCode cannot be blank" }
            require(normalized.length <= 100) { "ChannelCode cannot be longer than 100 characters" }
            return ChannelCode(normalized)
        }
    }
}
