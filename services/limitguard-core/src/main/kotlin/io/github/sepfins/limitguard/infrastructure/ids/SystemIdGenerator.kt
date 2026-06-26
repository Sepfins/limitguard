package io.github.sepfins.limitguard.infrastructure.ids

import io.github.sepfins.limitguard.port.ids.IdGenerator
import java.util.UUID
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.toJavaUuid

class SystemIdGenerator() : IdGenerator {
    @OptIn(ExperimentalUuidApi::class)
    override fun newId(): UUID = kotlin.uuid.Uuid.generateV7().toJavaUuid()
}
