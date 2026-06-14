package io.github.sepfins.limitguard.domain.limit

@JvmInline
value class LimitPriority private constructor(val value: Int) {
    companion object {
        fun of(value: Int): LimitPriority {
            require(value >= 0) { "LimitPriority must be non-negative" }
            return LimitPriority(value)
        }
    }
}
