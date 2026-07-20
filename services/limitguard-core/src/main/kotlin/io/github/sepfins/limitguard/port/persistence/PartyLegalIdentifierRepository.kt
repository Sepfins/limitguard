package io.github.sepfins.limitguard.port.persistence

import io.github.sepfins.limitguard.domain.common.PartyId
import io.github.sepfins.limitguard.domain.common.PartyLegalIdentifierId
import io.github.sepfins.limitguard.domain.party.JurisdictionCountry
import io.github.sepfins.limitguard.domain.party.LegalIdentifierType
import io.github.sepfins.limitguard.domain.party.LegalIdentifierValue
import io.github.sepfins.limitguard.domain.party.PartyLegalIdentifier

interface PartyLegalIdentifierRepository {
    fun findById(id: PartyLegalIdentifierId): PartyLegalIdentifier?

    fun findByPartyId(partyId: PartyId): List<PartyLegalIdentifier>

    fun findByValue(
        jurisdictionCountry: JurisdictionCountry,
        identifierType: LegalIdentifierType,
        normalizedIdentifier: LegalIdentifierValue
    ): PartyLegalIdentifier?

    fun insert(identifier: PartyLegalIdentifier): PartyLegalIdentifier

    fun update(identifier: PartyLegalIdentifier): PartyLegalIdentifier
}
