package io.github.sepfins.limitguard.port.ids

import java.util.UUID


interface IdGenerator {
    fun newId(): UUID
}