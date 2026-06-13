package io.github.sepfins.limitguard.api.commands

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

data class RegisterTenantCommand(
    val name: String,
    val config: Map<String, Any?> = emptyMap(),
    val idempotencyKey: String? = null,
)

data class RegisterChannelCommand(
    val tenantId: UUID,
    val code: String,
    val name: String,
    val isActive: Boolean = true,
    val metadata: Map<String, Any?> = emptyMap(),
)

data class RegisterOperationTypeCommand(
    val code: String,
    val name: String,
    val tenantId: UUID? = null,
    val parentId: UUID? = null,
    val isLeaf: Boolean = false,
    val isGlobal: Boolean = false,
)

data class RegisterPartyProfileCommand(
    val tenantId: UUID,
    val partyType: PartyType,
    val firstName: String? = null,
    val lastName: String? = null,
    val birthDate: LocalDate? = null,
    val legalName: String? = null,
    val legalForm: String? = null,
    val externalIdentifiers: List<EntityIdentifierCommand> = emptyList(),
    val metadata: Map<String, Any?> = emptyMap(),
)

data class EntityIdentifierCommand(
    val schemeName: String,
    val identifier: String,
)

data class RegisterFundingSourceProductCommand(
    val tenantId: UUID,
    val code: String,
    val name: String,
    val category: FundingSourceCategory,
    val nodeType: ProductNodeType,
    val parentId: UUID? = null,
    val isLeaf: Boolean = false,
    val isActive: Boolean = true,
    val metadata: Map<String, Any?> = emptyMap(),
)

data class RegisterFundingSourceCommand(
    val tenantId: UUID,
    val productId: UUID,
    val externalReference: String? = null,
    val isActive: Boolean = true,
    val metadata: Map<String, Any?> = emptyMap(),
)

data class AssignFundingSourceOwnerCommand(
    val tenantId: UUID,
    val partyProfileId: UUID,
    val fundingSourceId: UUID,
    val ownerRole: FundingSourceOwnerRole = FundingSourceOwnerRole.PRIMARY_OWNER,
)

data class CreateMandateCommand(
    val tenantId: UUID,
    val ownerPartyProfileId: UUID,
    val assigneePartyProfileId: UUID,
    val name: String,
    val description: String? = null,
    val authorityDelegationId: UUID? = null,
    val fundingScope: LimitFundingScope = LimitFundingScope.ALL_OWNER_SOURCES,
    val fundingSourceId: UUID? = null,
    val fundingSourceGroupId: UUID? = null,
    val startsAt: Instant? = null,
    val endsAt: Instant? = null,
    val metadata: Map<String, Any?> = emptyMap(),
)

data class CreateLimitConfigurationCommand(
    val tenantId: UUID,
    val code: String,
    val name: String,
    val limitKind: LimitKind,
    val currency: String,
    val maxAssignableAmount: BigDecimal,
    val parentConfigurationId: UUID? = null,
    val description: String? = null,
    val period: LimitPeriod? = null,
    val fundingScope: LimitConfigurationFundingScope = LimitConfigurationFundingScope.ALL_FUNDING_SOURCES,
    val fundingSourceCategory: FundingSourceCategory? = null,
    val fundingSourceProductId: UUID? = null,
    val channelScope: LimitChannelScope = LimitChannelScope.ALL_CHANNELS,
    val channelId: UUID? = null,
    val channelGroupId: UUID? = null,
    val operationTypeId: UUID? = null,
    val defaultAmount: BigDecimal? = null,
    val priority: Int = 100,
    val isActive: Boolean = true,
    val startsAt: Instant? = null,
    val endsAt: Instant? = null,
    val metadata: Map<String, Any?> = emptyMap(),
)
