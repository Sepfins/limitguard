package io.github.sepfins.limitguard.application.evaluation

import io.github.sepfins.limitguard.api.errors.ConflictException
import io.github.sepfins.limitguard.api.errors.NotFoundException
import io.github.sepfins.limitguard.domain.channel.Channel
import io.github.sepfins.limitguard.domain.common.FundingSourceProductId
import io.github.sepfins.limitguard.domain.common.TenantId
import io.github.sepfins.limitguard.domain.funding.FundingSource
import io.github.sepfins.limitguard.domain.funding.FundingSourceProduct
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
    fun resolve(transaction: PaymentTransaction): ResolvedAuthorizationReferences {
        val tenant = tenantRepository.findById(transaction.tenantId)
            ?: throw NotFoundException("Tenant not found: ${transaction.tenantId.value}")
        val channel = channelRepository.findById(transaction.tenantId, transaction.channelId)
            ?: throw NotFoundException(
                "Channel not found: ${transaction.tenantId.value} / ${transaction.channelId.value}",
            )
        operationTypeRepository.findById(transaction.tenantId, transaction.operationTypeId)
            ?: throw NotFoundException(
                "Operation type not found: ${transaction.tenantId.value} / ${transaction.operationTypeId.value}",
            )
        partyProfileRepository.findById(transaction.tenantId, transaction.actingPartyProfileId)
            ?: throw NotFoundException(
                "Party profile not found: ${transaction.tenantId.value} / " +
                    transaction.actingPartyProfileId.value,
            )
        val fundingSource = fundingSourceRepository.findById(transaction.tenantId, transaction.fundingSourceId)
            ?: throw NotFoundException(
                "Funding source not found: ${transaction.tenantId.value} / ${transaction.fundingSourceId.value}",
            )

        return ResolvedAuthorizationReferences(
            tenant = tenant,
            channel = channel,
            fundingSource = fundingSource,
            fundingSourceProductPath = requireProductPath(
                tenantId = transaction.tenantId,
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
