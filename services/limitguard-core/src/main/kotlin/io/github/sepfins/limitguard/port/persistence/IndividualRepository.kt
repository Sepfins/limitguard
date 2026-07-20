package io.github.sepfins.limitguard.port.persistence

import io.github.sepfins.limitguard.domain.common.PartyId
import io.github.sepfins.limitguard.domain.party.Individual

interface IndividualRepository {
    fun findByPartyId(partyId: PartyId): Individual?

    fun insert(individual: Individual): Individual

    fun update(individual: Individual): Individual
}
