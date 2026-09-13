package io.github.sepfins.limitguard.api.queries

import java.time.Instant
import java.util.UUID

data class GetLimitChangeQuery(
    val tenantId: UUID,
    val changeRequestId: UUID,
)

data class GetLimitRuleQuery(
    val tenantId: UUID,
    val limitRuleId: UUID,
)

data class GetTransactionHistoryQuery(
    val tenantId: UUID,
    val paymentTransactionId: UUID,
)

data class GetCurrentUsageQuery(
    val tenantId: UUID,
    val limitRuleId: UUID,
    val assigneePartyProfileId: UUID,
    val at: Instant? = null,
)

/**
 * Resolves the limits applicable to one operation without requiring callers to
 * discover internal rule identifiers first.
 */
data class GetOperationCapacityQuery(
    val tenantId: UUID,
    val actingPartyProfileId: UUID,
    val fundingSourceId: UUID,
    val operationTypeId: UUID,
    val channelId: UUID,
    val currency: String,
    val at: Instant? = null,
)

data class FindTenantByNameQuery(
    val name: String,
)

data class FindChannelByCodeQuery(
    val tenantId: UUID,
    val code: String,
)

data class FindOperationTypesByCodeQuery(
    val tenantId: UUID,
    val code: String,
)

data class FindFundingSourceProductByCodeQuery(
    val tenantId: UUID,
    val code: String,
)

data class FindFundingSourceByExternalReferenceQuery(
    val tenantId: UUID,
    val externalReference: String,
)

data class FindLimitConfigurationByCodeQuery(
    val tenantId: UUID,
    val code: String,
)
