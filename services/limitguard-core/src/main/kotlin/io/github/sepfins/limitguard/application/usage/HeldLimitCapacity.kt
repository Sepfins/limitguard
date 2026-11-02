package io.github.sepfins.limitguard.application.usage

import io.github.sepfins.limitguard.domain.common.PartyProfileId
import io.github.sepfins.limitguard.domain.common.TenantId

/** A cascade's capacities with every periodic bucket key locked for the surrounding transaction. */
internal class HeldLimitCapacity internal constructor(
    val tenantId: TenantId,
    val assigneePartyProfileId: PartyProfileId,
    val capacities: List<AvailableLimitCapacity>,
)
