package io.github.sepfins.limitguard.api.results

import io.github.sepfins.limitguard.api.FundingSourceCategory
import io.github.sepfins.limitguard.api.FundingSourceOwnerRole
import io.github.sepfins.limitguard.api.LimitChannelScope
import io.github.sepfins.limitguard.api.LimitConfigurationFundingScope
import io.github.sepfins.limitguard.api.LimitFundingScope
import io.github.sepfins.limitguard.api.LimitKind
import io.github.sepfins.limitguard.api.LimitPeriod
import io.github.sepfins.limitguard.api.PartyType
import io.github.sepfins.limitguard.api.ProductNodeType
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class TenantResult(
    val id: UUID,
    val name: String,
    val config: Map<String, Any?>,
    val createdAt: Instant,
)

data class ChannelResult(
    val id: UUID,
    val tenantId: UUID,
    val code: String,
    val name: String,
    val isActive: Boolean,
    val metadata: Map<String, Any?>,
    val createdAt: Instant,
)

data class OperationTypeResult(
    val id: UUID,
    val tenantId: UUID?,
    val parentId: UUID?,
    val code: String,
    val name: String,
    val isLeaf: Boolean,
    val isGlobal: Boolean,
    val createdAt: Instant,
)

data class PartyProfileResult(
    val id: UUID,
    val tenantId: UUID,
    val partyId: UUID,
    val partyType: PartyType,
    val firstName: String? = null,
    val lastName: String? = null,
    val birthDate: LocalDate? = null,
    val legalName: String? = null,
    val legalForm: String? = null,
    val createdAt: Instant,
)

data class FundingSourceProductResult(
    val id: UUID,
    val tenantId: UUID,
    val parentId: UUID?,
    val category: FundingSourceCategory,
    val nodeType: ProductNodeType,
    val code: String,
    val name: String,
    val isLeaf: Boolean,
    val isActive: Boolean,
    val metadata: Map<String, Any?>,
    val createdAt: Instant,
)

data class FundingSourceResult(
    val id: UUID,
    val tenantId: UUID,
    val productId: UUID,
    val externalReference: String?,
    val isActive: Boolean,
    val metadata: Map<String, Any?>,
    val createdAt: Instant,
)

data class FundingSourceOwnerResult(
    val id: UUID,
    val tenantId: UUID,
    val partyProfileId: UUID,
    val fundingSourceId: UUID,
    val ownerRole: FundingSourceOwnerRole,
    val createdAt: Instant,
)

data class MandateResult(
    val id: UUID,
    val tenantId: UUID,
    val ownerPartyProfileId: UUID,
    val assigneePartyProfileId: UUID,
    val name: String,
    val description: String?,
    val fundingScope: LimitFundingScope,
    val fundingSourceId: UUID?,
    val fundingSourceGroupId: UUID?,
    val isActive: Boolean,
    val version: Int,
    val startsAt: Instant,
    val endsAt: Instant?,
    val metadata: Map<String, Any?>,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class LimitConfigurationResult(
    val id: UUID,
    val tenantId: UUID,
    val parentConfigurationId: UUID?,
    val code: String,
    val name: String,
    val description: String?,
    val limitKind: LimitKind,
    val period: LimitPeriod?,
    val currency: String,
    val fundingScope: LimitConfigurationFundingScope,
    val fundingSourceCategory: FundingSourceCategory?,
    val fundingSourceProductId: UUID?,
    val channelScope: LimitChannelScope,
    val channelId: UUID?,
    val channelGroupId: UUID?,
    val operationTypeId: UUID?,
    val maxAssignableAmount: BigDecimal,
    val defaultAmount: BigDecimal?,
    val priority: Int,
    val isActive: Boolean,
    val startsAt: Instant,
    val endsAt: Instant?,
    val metadata: Map<String, Any?>,
    val createdAt: Instant,
    val updatedAt: Instant,
)
