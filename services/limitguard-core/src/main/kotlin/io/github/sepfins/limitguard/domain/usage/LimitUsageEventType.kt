package io.github.sepfins.limitguard.domain.usage

enum class LimitUsageEventType {
    RESERVE,
    CONSUME,
    RELEASE,
    REVERSAL,
    ADJUSTMENT,
}
