package io.github.sepfins.limitguard.api.commands

import io.github.sepfins.limitguard.api.FundingSourceCategory
import io.github.sepfins.limitguard.api.FundingSourceOwnerRole
import io.github.sepfins.limitguard.api.LimitChannelScope
import io.github.sepfins.limitguard.api.LimitChangePolicyDecision
import io.github.sepfins.limitguard.api.LimitChangePolicyEngine
import io.github.sepfins.limitguard.api.LimitChangePolicyPhase
import io.github.sepfins.limitguard.api.LimitConfigurationFundingScope
import io.github.sepfins.limitguard.api.LimitFundingScope
import io.github.sepfins.limitguard.api.LimitAmount
import io.github.sepfins.limitguard.api.LimitKind
import io.github.sepfins.limitguard.api.LimitPeriod
import io.github.sepfins.limitguard.api.NoMatchingRuleBehavior
import io.github.sepfins.limitguard.api.LimitUsageConsumptionMode
import io.github.sepfins.limitguard.api.PartyType
import io.github.sepfins.limitguard.api.PartyProfileDelegationType
import io.github.sepfins.limitguard.api.ProductNodeType
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class RegisterTenantCommand(
    val name: String,
    val config: Map<String, Any?> = emptyMap(),
    val noMatchingRuleBehavior: NoMatchingRuleBehavior = NoMatchingRuleBehavior.DENY,
)

data class RegisterIdentifierSchemeCommand(
    val tenantId: UUID,
    val entityType: String,
    val schemeName: String,
    val formatRegex: String? = null,
    val maxLength: Int? = null,
    val isPrimary: Boolean = false,
)

data class AssignEntityIdentifierCommand(
    val tenantId: UUID,
    val entityType: String,
    val entityId: UUID,
    val schemeName: String,
    val identifier: String,
)

data class RegisterChannelCommand(
    val tenantId: UUID,
    val code: String,
    val name: String,
    val isActive: Boolean = true,
    val metadata: Map<String, Any?> = emptyMap(),
)

data class UpdateChannelCommand(
    val tenantId: UUID,
    val channelId: UUID,
    val name: String,
    val metadata: Map<String, Any?>,
)

data class SetChannelActiveCommand(
    val tenantId: UUID,
    val channelId: UUID,
    val isActive: Boolean,
)

data class CreateChannelGroupCommand(
    val tenantId: UUID,
    val code: String,
    val name: String,
    val metadata: Map<String, Any?> = emptyMap(),
)

data class AddChannelGroupMemberCommand(
    val tenantId: UUID,
    val channelGroupId: UUID,
    val channelId: UUID,
)

data class RegisterOperationTypeCommand(
    val tenantId: UUID,
    val code: String,
    val name: String,
    val parentId: UUID? = null,
    val isLeaf: Boolean = false,
    val isGlobal: Boolean = false,
)

/** Host-verified onboarding details are required on both new registration and canonical reuse. */
data class RegisterPartyProfileCommand(
    val tenantId: UUID,
    val partyType: PartyType,
    val firstName: String? = null,
    val lastName: String? = null,
    val birthDate: LocalDate? = null,
    val legalName: String? = null,
    val legalForm: String? = null,
    val legalIdentifiers: List<PartyLegalIdentifierCommand> = emptyList(),
    /** Resolved against identifier schemes in the PARTY_PROFILE entity namespace. */
    val externalIdentifiers: List<EntityIdentifierCommand> = emptyList(),
    val metadata: Map<String, Any?> = emptyMap(),
)

data class PartyLegalIdentifierCommand(
    val jurisdictionCountry: String,
    val identifierType: String,
    val normalizedIdentifier: String,
    val metadata: Map<String, Any?> = emptyMap(),
)

data class EntityIdentifierCommand(
    val schemeName: String,
    val identifier: String,
)

data class CreatePartyProfileDelegationCommand(
    val tenantId: UUID,
    val ownerPartyProfileId: UUID,
    val delegatePartyProfileId: UUID,
    val delegationType: PartyProfileDelegationType,
    val startsAt: Instant? = null,
    val endsAt: Instant? = null,
    val metadata: Map<String, Any?> = emptyMap(),
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

data class UpdateFundingSourceProductCommand(
    val tenantId: UUID,
    val fundingSourceProductId: UUID,
    val name: String,
    val metadata: Map<String, Any?>,
)

data class SetFundingSourceProductActiveCommand(
    val tenantId: UUID,
    val fundingSourceProductId: UUID,
    val isActive: Boolean,
)

data class RegisterFundingSourceCommand(
    val tenantId: UUID,
    val productId: UUID,
    val externalReference: String? = null,
    val isActive: Boolean = true,
    val metadata: Map<String, Any?> = emptyMap(),
)

data class UpdateFundingSourceCommand(
    val tenantId: UUID,
    val fundingSourceId: UUID,
    val externalReference: String?,
    val metadata: Map<String, Any?>,
)

data class SetFundingSourceActiveCommand(
    val tenantId: UUID,
    val fundingSourceId: UUID,
    val isActive: Boolean,
)

data class AssignFundingSourceOwnerCommand(
    val tenantId: UUID,
    val partyProfileId: UUID,
    val fundingSourceId: UUID,
    val ownerRole: FundingSourceOwnerRole = FundingSourceOwnerRole.PRIMARY_OWNER,
)

data class CreateFundingSourceGroupCommand(
    val tenantId: UUID,
    val ownerPartyProfileId: UUID,
    val code: String,
    val name: String,
    val metadata: Map<String, Any?> = emptyMap(),
)

data class AddFundingSourceGroupMemberCommand(
    val tenantId: UUID,
    val fundingSourceGroupId: UUID,
    val fundingSourceId: UUID,
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
    val maxAssignableAmount: LimitAmount,
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
    val usageConsumptionMode: LimitUsageConsumptionMode = LimitUsageConsumptionMode.CASCADING,
    val isActive: Boolean = true,
    val startsAt: Instant? = null,
    val endsAt: Instant? = null,
    val metadata: Map<String, Any?> = emptyMap(),
)

/** Updates descriptive/defaulting fields; scope, currency, and caps remain stable. */
data class UpdateLimitConfigurationCommand(
    val tenantId: UUID,
    val limitConfigurationId: UUID,
    val name: String,
    val description: String?,
    val defaultAmount: BigDecimal?,
    val metadata: Map<String, Any?>,
)

data class SetLimitConfigurationActiveCommand(
    val tenantId: UUID,
    val limitConfigurationId: UUID,
    val isActive: Boolean,
)

data class CreateLimitChangePolicyCommand(
    val tenantId: UUID,
    val code: String,
    val name: String,
    val phase: LimitChangePolicyPhase,
    val description: String? = null,
    val engine: LimitChangePolicyEngine = LimitChangePolicyEngine.JSON_LOGIC,
    val decision: LimitChangePolicyDecision = LimitChangePolicyDecision.NO_OP,
    val limitConfigurationId: UUID? = null,
    val priority: Int = 100,
    val isActive: Boolean = true,
    val appliesTo: Map<String, Any?> = emptyMap(),
    val conditionExpression: Map<String, Any?> = emptyMap(),
    val actionExpression: List<Map<String, Any?>> = emptyList(),
    val denialCode: String? = null,
    val denialMessage: String? = null,
    val startsAt: Instant? = null,
    val endsAt: Instant? = null,
    val metadata: Map<String, Any?> = emptyMap(),
)
