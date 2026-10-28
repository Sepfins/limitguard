package io.github.sepfins.limitguard.api

import io.github.sepfins.limitguard.api.results.LimitChangeEventMessage

/**
 * The host's destination for relayed limit-change events, for example a broker topic.
 * Returning accepts the whole ordered batch; throwing accepts none of it.
 */
fun interface LimitChangeEventSink {
    fun relay(messages: List<LimitChangeEventMessage>)
}
