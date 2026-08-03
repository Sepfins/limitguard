package io.github.sepfins.limitguard.domain.limit

/**
 * Controls which periodic usage buckets a transaction mutates.
 *
 * Rules are processed by ascending priority within one funding-source owner.
 * CASCADING continues into broader priority tiers. ISOLATED includes its whole
 * priority tier and terminates the cascade before broader tiers. It has no
 * meaning for per-transaction rules.
 */
enum class LimitUsageConsumptionMode {
    CASCADING,
    ISOLATED,
}
