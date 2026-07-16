package io.github.sepfins.limitguard.domain.party

import io.github.sepfins.limitguard.domain.common.PartyId
import java.time.Instant

data class Party(
    val id: PartyId,
    val partyType: PartyType,
    val createdAt: Instant? = null
)
