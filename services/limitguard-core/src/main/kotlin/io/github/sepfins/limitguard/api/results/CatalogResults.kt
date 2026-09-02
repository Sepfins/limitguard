package io.github.sepfins.limitguard.api.results

import io.github.sepfins.limitguard.api.FundingSourceCategory
import io.github.sepfins.limitguard.api.FundingSourceOwnerRole
import io.github.sepfins.limitguard.api.LimitChannelScope
import io.github.sepfins.limitguard.api.LimitChangePolicyDecision
import io.github.sepfins.limitguard.api.LimitChangePolicyEngine
import io.github.sepfins.limitguard.api.LimitChangePolicyPhase
import io.github.sepfins.limitguard.api.LimitConfigurationFundingScope
import io.github.sepfins.limitguard.api.LimitFundingScope
import io.github.sepfins.limitguard.api.LimitKind
import io.github.sepfins.limitguard.api.LimitPeriod
import io.github.sepfins.limitguard.api.PartyType
import io.github.sepfins.limitguard.api.PartyProfileDelegationType
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

data class IdentifierSchemeResult(
    val id: UUID,
    val tenantId: UUID,
    val entityType: String,
    val schemeName: String,
    val formatRegex: String?,
    val maxLength: Int?,
    val isPrimary: Boolean,
    val createdAt: Instant,
)

data class EntityIdentifierResult(
    val id: UUID,
    val tenantId: UUID,
    val entityType: String,
    val entityId: UUID,
    val schemeId: UUID,
    val schemeName: String,
    val identifier: String,
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

data class ChannelGroupResult(
    val id: UUID,
    val tenantId: UUID,
    val code: String,
    val name: String,
    val metadata: Map<String, Any?>,
    val createdAt: Instant,
)

data class ChannelGroupMemberResult(
    val tenantId: UUID,
    val channelGroupId: UUID,
    val channelId: UUID,
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
    val partyCreated: Boolean = false,
    val firstName: String? = null,
    val lastName: String? = null,
    val birthDate: LocalDate? = null,
    val legalName: String? = null,
    val legalForm: String? = null,
    val legalIdentifiers: List<PartyLegalIdentifierResult> = emptyList(),
    val externalIdentifiers: List<EntityIdentifierResult> = emptyList(),
    val createdAt: Instant,
    val metadata: Map<String, Any?> = emptyMap(),
)

data class PartyLegalIdentifierResult(
    val id: UUID,
    val partyId: UUID,
    val jurisdictionCountry: String,
    val identifierType: String,
    val normalizedIdentifier: String,
    val metadata: Map<String, Any?>,
    val createdAt: Instant,
)

data class BankWorkerResult(
    val id: UUID,
    val tenantId: UUID,
    val externalReference: String,
    val displayName: String,
    val branchCode: String?,
    val isActive: Boolean,
    val metadata: Map<String, Any?>,
    val createdAt: Instant,
)

data class PartyProfileDelegationResult(
    val id: UUID,
    val tenantId: UUID,
    val ownerPartyProfileId: UUID,
    val delegatePartyProfileId: UUID,
    val delegationType: PartyProfileDelegationType,
    val startsAt: Instant,
    val endsAt: Instant?,
    val metadata: Map<String, Any?>,
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

data class FundingSourceGroupResult(
    val id: UUID,
    val tenantId: UUID,
    val ownerPartyProfileId: UUID,
    val code: String,
    val name: String,
    val metadata: Map<String, Any?>,
    val createdAt: Instant,
)

data class FundingSourceGroupMemberResult(
    val tenantId: UUID,
    val fundingSourceGroupId: UUID,
    val ownerPartyProfileId: UUID,
    val fundingSourceId: UUID,
    val createdAt: Instant,
)

data class MandateResult(
    val id: UUID,
    val tenantId: UUID,
    val ownerPartyProfileId: UUID,
    val assigneePartyProfileId: UUID,
    val authorityDelegationId: UUID?,
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

data class LimitChangePolicyResult(
    val id: UUID,
    val tenantId: UUID,
    val code: String,
    val name: String,
    val description: String?,
    val phase: LimitChangePolicyPhase,
    val engine: LimitChangePolicyEngine,
    val decision: LimitChangePolicyDecision,
    val limitConfigurationId: UUID?,
    val priority: Int,
    val isActive: Boolean,
    val appliesTo: Map<String, Any?>,
    val conditionExpression: Map<String, Any?>,
    val actionExpression: List<Map<String, Any?>>,
    val denialCode: String?,
    val denialMessage: String?,
    val startsAt: Instant,
    val endsAt: Instant?,
    val metadata: Map<String, Any?>,
    val createdAt: Instant,
    val updatedAt: Instant,
)
