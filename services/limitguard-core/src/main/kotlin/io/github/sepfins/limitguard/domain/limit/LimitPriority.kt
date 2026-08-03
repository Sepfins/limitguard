package io.github.sepfins.limitguard.domain.limit

/**
 * Lower numbers are evaluated first. Rules with the same number form one peer
 * tier, so an ISOLATED periodic rule cannot suppress a peer arbitrarily.
 */
@JvmInline
value class LimitPriority private constructor(val value: Int) {
    companion object {
        fun of(value: Int): LimitPriority {
            require(value >= 0) { "LimitPriority must be non-negative" }
            return LimitPriority(value)
        }
    }
}
