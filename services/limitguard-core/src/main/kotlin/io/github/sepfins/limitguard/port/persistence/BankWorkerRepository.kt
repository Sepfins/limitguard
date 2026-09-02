package io.github.sepfins.limitguard.port.persistence

import io.github.sepfins.limitguard.domain.common.BankWorkerId
import io.github.sepfins.limitguard.domain.common.ExternalReference
import io.github.sepfins.limitguard.domain.common.TenantId
import io.github.sepfins.limitguard.domain.worker.BankWorker

interface BankWorkerRepository {
    fun findById(tenantId: TenantId, id: BankWorkerId): BankWorker?
    fun findByExternalReference(tenantId: TenantId, externalReference: ExternalReference): BankWorker?
    fun insert(tenantId: TenantId, bankWorker: BankWorker): BankWorker
    fun update(tenantId: TenantId, bankWorker: BankWorker): BankWorker
}
