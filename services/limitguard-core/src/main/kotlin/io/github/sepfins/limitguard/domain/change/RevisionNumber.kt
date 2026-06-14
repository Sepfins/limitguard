package io.github.sepfins.limitguard.domain.change

@JvmInline
value class RevisionNumber private constructor(val value: Int) {
    companion object {
        fun of(value: Int): RevisionNumber {
            require(value > 0) { "RevisionNumber must be positive" }
            return RevisionNumber(value)
        }
    }
}
