package io.github.sepfins.limitguard.port.persistence

import io.github.sepfins.limitguard.domain.common.PartyId
import io.github.sepfins.limitguard.domain.party.LegalEntity

interface LegalEntityRepository {
    fun findByPartyId(partyId: PartyId): LegalEntity?

    fun insert(legalEntity: LegalEntity): LegalEntity

    fun update(legalEntity: LegalEntity): LegalEntity
}
