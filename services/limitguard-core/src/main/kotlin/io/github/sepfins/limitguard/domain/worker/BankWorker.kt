package io.github.sepfins.limitguard.domain.worker

import io.github.sepfins.limitguard.domain.common.BankWorkerId
import io.github.sepfins.limitguard.domain.common.ExternalReference
import io.github.sepfins.limitguard.domain.common.Metadata
import io.github.sepfins.limitguard.domain.common.TenantId
import kotlin.time.Instant

// Tenant-scoped bank staff who can perform assisted limit changes for customers.
data class BankWorker(
    val id: BankWorkerId,
    val tenantId: TenantId,
    val externalReference: ExternalReference,
    val displayName: DisplayName,
    val branchCode: BranchCode? = null,
    val isActive: Boolean = true,
    val metadata: Metadata = Metadata.EMPTY,
    val createdAt: Instant? = null
)
