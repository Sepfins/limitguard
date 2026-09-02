package io.github.sepfins.limitguard.port.persistence

import io.github.sepfins.limitguard.domain.common.PartyProfileDelegationId
import io.github.sepfins.limitguard.domain.common.PartyProfileId
import io.github.sepfins.limitguard.domain.common.TenantId
import io.github.sepfins.limitguard.domain.party.PartyProfileDelegation

interface PartyProfileDelegationRepository {
    fun findById(tenantId: TenantId, id: PartyProfileDelegationId): PartyProfileDelegation?
    fun findByOwner(tenantId: TenantId, ownerPartyProfileId: PartyProfileId): List<PartyProfileDelegation>
    fun insert(tenantId: TenantId, delegation: PartyProfileDelegation): PartyProfileDelegation
    fun update(tenantId: TenantId, delegation: PartyProfileDelegation): PartyProfileDelegation
}
