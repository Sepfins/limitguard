package io.github.sepfins.limitguard.domain.usage

// Result of applying one transaction event to limit usage: the buckets that were
// touched (with their updated reserved/consumed amounts) and the usage events
// emitted to record the change. A single transaction event can affect several
// cascading buckets at once.
data class UsageMutation(
    val buckets: List<LimitUsageBucket> = emptyList(),
    val events: List<LimitUsageEvent> = emptyList()
)
