package io.github.sepfins.limitguard.domain.common

@JvmInline
value class Version private constructor(val value: Int) {
    companion object {
        val INITIAL: Version = Version(1)

        fun of(value: Int): Version {
            require(value > 0) { "Version must be positive" }
            return Version(value)
        }
    }
}
