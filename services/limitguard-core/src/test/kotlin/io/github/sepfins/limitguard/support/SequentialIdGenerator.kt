package io.github.sepfins.limitguard.support

import io.github.sepfins.limitguard.port.ids.IdGenerator
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

class SequentialIdGenerator(startAt: Long = 1L) : IdGenerator {
    private val nextValue = AtomicLong(startAt)

    init {
        require(startAt >= 0) { "startAt must be non-negative" }
    }

    override fun newId(): UUID = UUID(0L, nextValue.getAndIncrement())
}
