package io.github.sepfins.limitguard.application.evaluation

import io.github.sepfins.limitguard.api.errors.ConflictException
import io.github.sepfins.limitguard.api.errors.NotFoundException
import io.github.sepfins.limitguard.application.operation.OperationTypeAncestry
import io.github.sepfins.limitguard.domain.channel.Channel
import io.github.sepfins.limitguard.domain.common.FundingSourceProductId
import io.github.sepfins.limitguard.domain.common.TenantId
import io.github.sepfins.limitguard.domain.funding.FundingSource
import io.github.sepfins.limitguard.domain.funding.FundingSourceProduct
import io.github.sepfins.limitguard.domain.operation.OperationType
import io.github.sepfins.limitguard.domain.tenant.Tenant
import io.github.sepfins.limitguard.domain.transaction.PaymentTransaction
import io.github.sepfins.limitguard.port.persistence.ChannelRepository
import io.github.sepfins.limitguard.port.persistence.FundingSourceProductRepository
import io.github.sepfins.limitguard.port.persistence.FundingSourceRepository
import io.github.sepfins.limitguard.port.persistence.OperationTypeRepository
import io.github.sepfins.limitguard.port.persistence.PartyProfileRepository
import io.github.sepfins.limitguard.port.persistence.TenantRepository

internal class AuthorizationReferenceResolver(
    private val tenantRepository: TenantRepository,
    private val channelRepository: ChannelRepository,
    private val operationTypeRepository: OperationTypeRepository,
    private val partyProfileRepository: PartyProfileRepository,
    private val fundingSourceRepository: FundingSourceRepository,
    private val fundingSourceProductRepository: FundingSourceProductRepository,
) {
    private val operationTypeAncestry = OperationTypeAncestry(operationTypeRepository)

    fun resolve(transaction: PaymentTransaction): ResolvedAuthorizationReferences =
        resolve(transaction.toLimitEvaluationContext())

    fun resolve(context: LimitEvaluationContext): ResolvedAuthorizationReferences {
        val tenant = tenantRepository.findById(context.tenantId)
            ?: throw NotFoundException("Tenant not found: ${context.tenantId.value}")
        val channel = channelRepository.findById(context.tenantId, context.channelId)
            ?: throw NotFoundException(
                "Channel not found: ${context.tenantId.value} / ${context.channelId.value}",
            )
        val operationTypePath = operationTypeAncestry.resolve(context.tenantId, context.operationTypeId)
        partyProfileRepository.findById(context.tenantId, context.actingPartyProfileId)
            ?: throw NotFoundException(
                "Party profile not found: ${context.tenantId.value} / " +
                    context.actingPartyProfileId.value,
            )
        val fundingSource = fundingSourceRepository.findById(context.tenantId, context.fundingSourceId)
            ?: throw NotFoundException(
                "Funding source not found: ${context.tenantId.value} / ${context.fundingSourceId.value}",
            )

        return ResolvedAuthorizationReferences(
            tenant = tenant,
            channel = channel,
            fundingSource = fundingSource,
            operationTypePath = operationTypePath,
            fundingSourceProductPath = requireProductPath(
                tenantId = context.tenantId,
                leafProductId = fundingSource.productId,
            ),
        )
    }

    private fun requireProductPath(
        tenantId: TenantId,
        leafProductId: FundingSourceProductId,
    ): List<FundingSourceProduct> {
        val pathById = fundingSourceProductRepository.findPath(tenantId, leafProductId).associateBy { it.id }
        val products = mutableListOf<FundingSourceProduct>()
        val visited = hashSetOf<FundingSourceProductId>()
        var currentId: FundingSourceProductId? = leafProductId

        while (currentId != null) {
            if (!visited.add(currentId)) {
                throw ConflictException(
                    "Funding source product hierarchy contains a cycle at ${currentId.value}",
                )
            }
            val product = pathById[currentId]
                ?: throw NotFoundException(
                    "Funding source product not found: ${tenantId.value} / ${currentId.value}",
                )
            products += product
            currentId = product.parentId
        }
        return products
    }
}

internal data class ResolvedAuthorizationReferences(
    val tenant: Tenant,
    val channel: Channel,
    val fundingSource: FundingSource,
    val operationTypePath: List<OperationType>,
    val fundingSourceProductPath: List<FundingSourceProduct>,
) {
    fun unavailableReferenceViolations(): List<Pair<String, String>> = buildList {
        if (!channel.isActive) {
            add(
                "CHANNEL_INACTIVE" to
                    "Channel ${channel.id.value} is inactive and cannot authorize new transactions",
            )
        }
        if (!fundingSource.isActive) {
            add(
                "FUNDING_SOURCE_INACTIVE" to
                    "Funding source ${fundingSource.id.value} is inactive and cannot authorize new transactions",
            )
        }
        fundingSourceProductPath
            .filterNot { it.isActive }
            .forEach { product ->
                add(
                    "FUNDING_SOURCE_PRODUCT_INACTIVE" to
                        "Funding source product ${product.id.value} is inactive and disables its descendants",
                )
            }
    }
}
