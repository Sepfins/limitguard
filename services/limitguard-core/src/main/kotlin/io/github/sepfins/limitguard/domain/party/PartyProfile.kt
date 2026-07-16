package io.github.sepfins.limitguard.domain.party

import io.github.sepfins.limitguard.domain.common.PartyId
import io.github.sepfins.limitguard.domain.common.PartyProfileId
import io.github.sepfins.limitguard.domain.common.TenantId
import java.time.Instant

data class PartyProfile(
    val id: PartyProfileId,
    val partyId: PartyId,
    val tenantId: TenantId,
    val createdAt: Instant? = null
)
