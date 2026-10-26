package io.github.sepfins.limitguard.admin.web.dto

import io.github.sepfins.limitguard.api.FundingSourceCategory
import io.github.sepfins.limitguard.api.LimitAmount
import io.github.sepfins.limitguard.api.LimitChannelScope
import io.github.sepfins.limitguard.api.LimitConfigurationFundingScope
import io.github.sepfins.limitguard.api.LimitKind
import io.github.sepfins.limitguard.api.LimitPeriod
import io.github.sepfins.limitguard.api.LimitUsageConsumptionMode
import io.github.sepfins.limitguard.api.NoMatchingRuleBehavior
import io.github.sepfins.limitguard.api.ProductNodeType
import io.github.sepfins.limitguard.api.results.ChannelGroupMemberResult
import io.github.sepfins.limitguard.api.results.ChannelGroupResult
import io.github.sepfins.limitguard.api.results.ChannelResult
import io.github.sepfins.limitguard.api.results.FundingSourceProductResult
import io.github.sepfins.limitguard.api.results.LimitConfigurationResult
import io.github.sepfins.limitguard.api.results.OperationTypeResult
import io.github.sepfins.limitguard.api.results.PageResult
import io.github.sepfins.limitguard.api.results.TenantResult
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

data class MeResponse(
    val subject: String,
    val displayName: String,
    val email: String?,
    val roles: Set<String>,
    val tenantIds: Set<UUID>,
    val logoutUrl: String,
    val csrfParameterName: String,
    val csrfToken: String,
)

data class CreateTenantRequest(
    @field:NotBlank
    @field:Size(max = 255)
    val name: String,
    val noMatchingRuleBehavior: NoMatchingRuleBehavior = NoMatchingRuleBehavior.DENY,
)

data class TenantResponse(
    val id: UUID,
    val name: String,
    val noMatchingRuleBehavior: NoMatchingRuleBehavior,
    val createdAt: Instant,
)

data class CreateFundingSourceProductRequest(
    @field:NotBlank
    @field:Size(max = 100)
    val code: String,
    @field:NotBlank
    @field:Size(max = 255)
    val name: String,
    val category: FundingSourceCategory,
    val nodeType: ProductNodeType,
    val parentId: UUID? = null,
    val isLeaf: Boolean = false,
    val isActive: Boolean = true,
)

data class FundingSourceProductResponse(
    val id: UUID,
    val tenantId: UUID,
    val parentId: UUID?,
    val category: FundingSourceCategory,
    val nodeType: ProductNodeType,
    val code: String,
    val name: String,
    val isLeaf: Boolean,
    val isActive: Boolean,
    val createdAt: Instant,
)

data class CreateChannelRequest(
    @field:NotBlank
    @field:Size(max = 100)
    val code: String,
    @field:NotBlank
    @field:Size(max = 255)
    val name: String,
)

data class SetChannelActiveRequest(
    @field:NotNull
    val isActive: Boolean?,
)

data class ChannelResponse(
    val id: UUID,
    val code: String,
    val name: String,
    val isActive: Boolean,
    val createdAt: Instant,
)

data class CreateChannelGroupRequest(
    @field:NotBlank
    @field:Size(max = 100)
    val code: String,
    @field:NotBlank
    @field:Size(max = 255)
    val name: String,
)

data class AddChannelGroupMemberRequest(
    @field:NotNull
    val channelId: UUID?,
)

data class ChannelGroupResponse(
    val id: UUID,
    val code: String,
    val name: String,
    val memberChannelIds: List<UUID>,
    val createdAt: Instant,
)

data class ChannelGroupMemberResponse(
    val channelGroupId: UUID,
    val channelId: UUID,
    val createdAt: Instant,
)

data class CreateOperationTypeRequest(
    @field:NotBlank
    @field:Size(max = 100)
    val code: String,
    @field:NotBlank
    @field:Size(max = 255)
    val name: String,
    val parentId: UUID? = null,
    val isLeaf: Boolean = false,
    val isGlobal: Boolean = false,
)

data class OperationTypeResponse(
    val id: UUID,
    val parentId: UUID?,
    val code: String,
    val name: String,
    val isLeaf: Boolean,
    val isGlobal: Boolean,
    val createdAt: Instant,
)

private const val DECIMAL_AMOUNT = "\\d{1,16}(\\.\\d{1,4})?"

/** Amounts are decimal strings; an unlimited maximum is the flag with no amount. */
data class CreateLimitConfigurationRequest(
    @field:NotBlank
    @field:Size(max = 100)
    val code: String,
    @field:NotBlank
    @field:Size(max = 255)
    val name: String,
    val description: String? = null,
    @field:NotNull
    val limitKind: LimitKind?,
    val period: LimitPeriod? = null,
    @field:NotBlank
    @field:Size(min = 3, max = 3)
    val currency: String,
    @field:Pattern(regexp = DECIMAL_AMOUNT)
    val maxAssignableAmount: String? = null,
    val maxAssignableUnlimited: Boolean = false,
    @field:Pattern(regexp = DECIMAL_AMOUNT)
    val defaultAmount: String? = null,
    val parentConfigurationId: UUID? = null,
    val fundingScope: LimitConfigurationFundingScope = LimitConfigurationFundingScope.ALL_FUNDING_SOURCES,
    val fundingSourceCategory: FundingSourceCategory? = null,
    val fundingSourceProductId: UUID? = null,
    val channelScope: LimitChannelScope = LimitChannelScope.ALL_CHANNELS,
    val channelId: UUID? = null,
    val channelGroupId: UUID? = null,
    val operationTypeId: UUID? = null,
    val priority: Int = 100,
    val usageConsumptionMode: LimitUsageConsumptionMode = LimitUsageConsumptionMode.CASCADING,
    val startsAt: Instant? = null,
    val endsAt: Instant? = null,
)

data class UpdateLimitConfigurationRequest(
    @field:NotBlank
    @field:Size(max = 255)
    val name: String,
    val description: String? = null,
    @field:Pattern(regexp = DECIMAL_AMOUNT)
    val defaultAmount: String? = null,
)

data class SetLimitConfigurationActiveRequest(
    @field:NotNull
    val isActive: Boolean?,
)

data class LimitConfigurationResponse(
    val id: UUID,
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
    val maxAssignableAmount: String?,
    val maxAssignableUnlimited: Boolean,
    val defaultAmount: String?,
    val priority: Int,
    val usageConsumptionMode: LimitUsageConsumptionMode,
    val isActive: Boolean,
    val startsAt: Instant,
    val endsAt: Instant?,
    val createdAt: Instant,
)

data class PageResponse<T>(
    val items: List<T>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
    val totalPages: Int,
)

fun TenantResult.toAdminResponse(): TenantResponse = TenantResponse(
    id = id,
    name = name,
    noMatchingRuleBehavior = noMatchingRuleBehavior,
    createdAt = createdAt,
)

fun FundingSourceProductResult.toAdminResponse(): FundingSourceProductResponse =
    FundingSourceProductResponse(
        id = id,
        tenantId = tenantId,
        parentId = parentId,
        category = category,
        nodeType = nodeType,
        code = code,
        name = name,
        isLeaf = isLeaf,
        isActive = isActive,
        createdAt = createdAt,
    )

fun ChannelResult.toAdminResponse(): ChannelResponse = ChannelResponse(
    id = id,
    code = code,
    name = name,
    isActive = isActive,
    createdAt = createdAt,
)

fun ChannelGroupResult.toAdminResponse(): ChannelGroupResponse = ChannelGroupResponse(
    id = id,
    code = code,
    name = name,
    memberChannelIds = memberChannelIds,
    createdAt = createdAt,
)

fun ChannelGroupMemberResult.toAdminResponse(): ChannelGroupMemberResponse = ChannelGroupMemberResponse(
    channelGroupId = channelGroupId,
    channelId = channelId,
    createdAt = createdAt,
)

fun OperationTypeResult.toAdminResponse(): OperationTypeResponse = OperationTypeResponse(
    id = id,
    parentId = parentId,
    code = code,
    name = name,
    isLeaf = isLeaf,
    isGlobal = isGlobal,
    createdAt = createdAt,
)

fun LimitConfigurationResult.toAdminResponse(): LimitConfigurationResponse = LimitConfigurationResponse(
    id = id,
    parentConfigurationId = parentConfigurationId,
    code = code,
    name = name,
    description = description,
    limitKind = limitKind,
    period = period,
    currency = currency,
    fundingScope = fundingScope,
    fundingSourceCategory = fundingSourceCategory,
    fundingSourceProductId = fundingSourceProductId,
    channelScope = channelScope,
    channelId = channelId,
    channelGroupId = channelGroupId,
    operationTypeId = operationTypeId,
    maxAssignableAmount = (maxAssignableAmount as? LimitAmount.Bounded)?.value?.toAmountString(),
    maxAssignableUnlimited = maxAssignableAmount is LimitAmount.Unlimited,
    defaultAmount = defaultAmount?.toAmountString(),
    priority = priority,
    usageConsumptionMode = usageConsumptionMode,
    isActive = isActive,
    startsAt = startsAt,
    endsAt = endsAt,
    createdAt = createdAt,
)

private fun BigDecimal.toAmountString(): String = stripTrailingZeros().toPlainString()

fun <T, R> PageResult<T>.toAdminResponse(mapper: (T) -> R): PageResponse<R> = PageResponse(
    items = items.map(mapper),
    page = page,
    size = size,
    totalElements = totalElements,
    totalPages = totalPages,
)
