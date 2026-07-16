package io.github.sepfins.limitguard.port.persistence

import io.github.sepfins.limitguard.domain.common.MandateId
import io.github.sepfins.limitguard.domain.common.PartyProfileId
import io.github.sepfins.limitguard.domain.common.TenantId
import io.github.sepfins.limitguard.domain.funding.FundingSourceScope
import io.github.sepfins.limitguard.domain.mandate.Mandate
import java.time.Instant

interface MandateRepository {
    fun findActiveForAssignee(tenantId: TenantId, assignee: PartyProfileId, at: Instant): List<Mandate>
    fun findActiveForOwner(tenantId: TenantId, owner: PartyProfileId, at: Instant): List<Mandate>
    fun findActiveForOwnerWithScope(tenantId: TenantId, owner: PartyProfileId, at: Instant, scope: FundingSourceScope): List<Mandate>
    fun findById(tenantId: TenantId, id: MandateId): Mandate?
    fun insert(tenantId: TenantId, mandate: Mandate): Mandate
    fun update(tenantId: TenantId, mandate: Mandate): Mandate
}