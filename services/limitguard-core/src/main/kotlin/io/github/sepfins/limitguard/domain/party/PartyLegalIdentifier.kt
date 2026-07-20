package io.github.sepfins.limitguard.domain.party

import io.github.sepfins.limitguard.domain.common.Metadata
import io.github.sepfins.limitguard.domain.common.PartyId
import io.github.sepfins.limitguard.domain.common.PartyLegalIdentifierId
import java.time.Instant

data class PartyLegalIdentifier(
    val id: PartyLegalIdentifierId,
    val partyId: PartyId,
    val jurisdictionCountry: JurisdictionCountry,
    val identifierType: LegalIdentifierType,
    val normalizedIdentifier: LegalIdentifierValue,
    val metadata: Metadata = Metadata.EMPTY,
    val createdAt: Instant? = null
)
