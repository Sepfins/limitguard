package io.github.sepfins.limitguard.port.persistence

import io.github.sepfins.limitguard.domain.common.TenantId
import io.github.sepfins.limitguard.domain.tenant.Tenant
import io.github.sepfins.limitguard.domain.tenant.TenantName

interface TenantRepository {
    fun exists(tenantId: TenantId): Boolean
    fun findById(tenantId: TenantId): Tenant?
    fun findByName(name: TenantName): Tenant?
    fun insert(tenant: Tenant): Tenant
    fun update(tenant: Tenant): Tenant
}
