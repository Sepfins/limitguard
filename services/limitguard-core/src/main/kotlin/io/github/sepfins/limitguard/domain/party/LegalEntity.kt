package io.github.sepfins.limitguard.domain.party

import io.github.sepfins.limitguard.domain.common.PartyId

// One per party of type LEGAL_ENTITY.
data class LegalEntity(
    val partyId: PartyId,
    val legalName: LegalName,
    val legalForm: LegalForm? = null
)
