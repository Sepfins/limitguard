package io.github.sepfins.limitguard.port.persistence

import io.github.sepfins.limitguard.domain.common.PartyId
import io.github.sepfins.limitguard.domain.common.PartyProfileId
import io.github.sepfins.limitguard.domain.common.TenantId
import io.github.sepfins.limitguard.domain.party.PartyProfile

interface PartyProfileRepository {
    fun findById(tenantId: TenantId, id: PartyProfileId): PartyProfile?
    fun findByPartyId(tenantId: TenantId, partyId: PartyId): List<PartyProfile>
    fun insert(tenantId: TenantId, partyProfile: PartyProfile): PartyProfile
    fun update(tenantId: TenantId, partyProfile: PartyProfile): PartyProfile
}