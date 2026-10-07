package io.github.sepfins.limitguard.support

import io.github.sepfins.limitguard.api.FundingSourceCategory
import io.github.sepfins.limitguard.api.Limitguard
import io.github.sepfins.limitguard.api.ProductNodeType
import io.github.sepfins.limitguard.api.commands.RegisterFundingSourceCommand
import io.github.sepfins.limitguard.api.commands.RegisterFundingSourceProductCommand
import io.github.sepfins.limitguard.api.commands.RegisterTenantCommand
import io.github.sepfins.limitguard.api.queries.FindFundingSourceByExternalReferenceQuery
import io.github.sepfins.limitguard.api.queries.FindFundingSourceProductByCodeQuery
import java.util.UUID

class FundingProductScenario(val facade: Limitguard) {
    val tenant = facade.tenantCatalogService.registerTenant(
        RegisterTenantCommand("Funding bank ${UUID.randomUUID()}"),
    ).id

    fun product(code: String, parent: UUID? = null, leaf: Boolean = false, active: Boolean = true) =
        facade.tenantCatalogService.registerFundingSourceProduct(RegisterFundingSourceProductCommand(
            tenantId = tenant,
            code = code,
            name = code,
            category = FundingSourceCategory.TRANSACTIONAL,
            nodeType = if (leaf) ProductNodeType.PRODUCT else ProductNodeType.FAMILY,
            parentId = parent,
            isLeaf = leaf,
            isActive = active,
        ))

    fun source(product: UUID, reference: String) = facade.tenantCatalogService.registerFundingSource(
        RegisterFundingSourceCommand(tenant, product, externalReference = reference),
    )

    fun findProduct(code: String) = facade.limitInquiryService.findFundingSourceProductByCode(
        FindFundingSourceProductByCodeQuery(tenant, code),
    )

    fun findSource(reference: String) = facade.limitInquiryService.findFundingSourceByExternalReference(
        FindFundingSourceByExternalReferenceQuery(tenant, reference),
    )
}
