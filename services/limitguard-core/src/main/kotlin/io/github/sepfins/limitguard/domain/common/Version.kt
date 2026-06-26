package io.github.sepfins.limitguard.domain.common

@JvmInline
value class Version(val version: Int) {
    init {
        require(version > 0) { "Version must be positive" }
    }
}
