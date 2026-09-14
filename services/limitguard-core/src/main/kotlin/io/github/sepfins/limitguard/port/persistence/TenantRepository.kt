package io.github.sepfins.limitguard.port.persistence

import io.github.sepfins.limitguard.domain.common.TenantId
import io.github.sepfins.limitguard.domain.tenant.Tenant
import io.github.sepfins.limitguard.domain.tenant.TenantName

interface TenantRepository {
    fun exists(tenantId: TenantId): Boolean
    fun findById(tenantId: TenantId): Tenant?
    fun findByName(name: TenantName): Tenant?
    fun findPage(search: String?, offset: Int, limit: Int): List<Tenant>
    fun count(search: String?): Long
    fun insert(tenant: Tenant): Tenant
    fun update(tenant: Tenant): Tenant
}
