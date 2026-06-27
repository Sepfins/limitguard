package io.github.sepfins.limitguard.port.persistence

import io.github.sepfins.limitguard.domain.common.PartyId
import io.github.sepfins.limitguard.domain.party.Party

interface PartyRepository {
    fun findById(id: PartyId): Party?
    fun insert(party: Party): Party
    fun update(party: Party): Party
}