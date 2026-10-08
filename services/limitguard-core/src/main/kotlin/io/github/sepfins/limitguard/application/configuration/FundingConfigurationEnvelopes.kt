package io.github.sepfins.limitguard.application.configuration

import io.github.sepfins.limitguard.api.errors.ConflictException
import io.github.sepfins.limitguard.api.errors.NotFoundException
import io.github.sepfins.limitguard.domain.common.FundingSourceProductId
import io.github.sepfins.limitguard.domain.funding.FundingSourceProduct
import io.github.sepfins.limitguard.domain.limit.LimitConfiguration
import io.github.sepfins.limitguard.domain.limit.LimitConfigurationFundingScope
import io.github.sepfins.limitguard.domain.limit.appliesToFundingSource
import io.github.sepfins.limitguard.domain.common.LimitConfigurationId
import io.github.sepfins.limitguard.port.persistence.FundingSourceProductRepository
import io.github.sepfins.limitguard.port.persistence.LimitConfigurationRepository

internal class FundingConfigurationEnvelopes(
    private val configurations: LimitConfigurationRepository,
    private val products: FundingSourceProductRepository,
) {
    fun validateHierarchy(configuration: LimitConfiguration) {
        validateHierarchy(configuration, FundingCoverage(products.findAll(configuration.tenantId))) {
            configurations.findById(configuration.tenantId, it)
        }
    }

    fun validateProductGrowth(product: FundingSourceProduct) {
        val coverage = FundingCoverage(products.findAll(product.tenantId) + product)
        val all = configurations.findAll(product.tenantId)
        val byId = all.associateBy { it.id }
        all.forEach { configuration -> validateHierarchy(configuration, coverage) { byId[it] } }
    }

    private fun validateHierarchy(
        configuration: LimitConfiguration,
        coverage: FundingCoverage,
        find: (LimitConfigurationId) -> LimitConfiguration?,
    ) {
        val hierarchy = listOf(configuration) + configuration.parentConfigurationId
            ?.let { resolveHierarchy(it, find) }.orEmpty()
        hierarchy.forEach { coverage.requireReference(it.fundingScope) }
        hierarchy.zipWithNext().forEach { (child, parent) ->
            child.validateAgainstParent(parent)
            require(coverage.contains(parent.fundingScope, child.fundingScope)) {
                "configuration ${child.id.value} funding coverage exceeds parent ${parent.id.value}; correct the funding envelope explicitly"
            }
        }
    }

    private class FundingCoverage(products: List<FundingSourceProduct>) {
        private val byId = products.associateBy { it.id }
        private val parentIds = products.mapNotNull { it.parentId }.toSet()
        private val leafPaths by lazy {
            products.filter { it.isLeaf && it.id !in parentIds }.map { path(it.id) }
        }

        fun requireReference(scope: LimitConfigurationFundingScope) {
            if (scope is LimitConfigurationFundingScope.FundingSourceProductSpecific) path(scope.productId)
        }

        fun contains(parent: LimitConfigurationFundingScope, child: LimitConfigurationFundingScope): Boolean {
            if (parent == LimitConfigurationFundingScope.AllFundingSources || parent == child) return true
            if (child == LimitConfigurationFundingScope.AllFundingSources) return false
            return leafPaths.filter { child.appliesToFundingSource(it) }.all { parent.appliesToFundingSource(it) }
        }

        private fun path(id: FundingSourceProductId): List<FundingSourceProduct> {
            val path = mutableListOf<FundingSourceProduct>()
            val visited = mutableSetOf<FundingSourceProductId>()
            var next: FundingSourceProductId? = id
            while (next != null) {
                if (!visited.add(next)) throw ConflictException("funding source product hierarchy contains a cycle: ${next.value}")
                val product = byId[next] ?: throw NotFoundException("funding source product not found: ${next.value}")
                path += product
                next = product.parentId
            }
            return path
        }
    }
}
