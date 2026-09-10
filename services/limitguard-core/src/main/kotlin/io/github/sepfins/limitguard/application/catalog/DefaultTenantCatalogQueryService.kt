package io.github.sepfins.limitguard.application.catalog

import io.github.sepfins.limitguard.api.TenantCatalogQueryService
import io.github.sepfins.limitguard.api.errors.NotFoundException
import io.github.sepfins.limitguard.api.errors.ValidationException
import io.github.sepfins.limitguard.api.queries.ResolveEntityIdentifierQuery
import io.github.sepfins.limitguard.api.results.EntityIdentifierResult
import io.github.sepfins.limitguard.domain.common.TenantId
import io.github.sepfins.limitguard.domain.identifier.EntityType
import io.github.sepfins.limitguard.domain.identifier.IdentifierValue
import io.github.sepfins.limitguard.domain.identifier.SchemeName
import io.github.sepfins.limitguard.port.persistence.EntityIdentifierRepository
import io.github.sepfins.limitguard.port.persistence.IdentifierSchemeRepository
import io.github.sepfins.limitguard.port.persistence.TenantRepository
import io.github.sepfins.limitguard.port.transactions.TransactionRunner

class DefaultTenantCatalogQueryService(
    private val tenantRepository: TenantRepository,
    private val identifierSchemeRepository: IdentifierSchemeRepository,
    private val entityIdentifierRepository: EntityIdentifierRepository,
    private val transactionRunner: TransactionRunner,
) : TenantCatalogQueryService {
    override fun resolveEntityIdentifier(
        query: ResolveEntityIdentifierQuery,
    ): EntityIdentifierResult? = mapValidation {
        val tenantId = TenantId.of(query.tenantId)
        val entityType = EntityType.of(query.entityType)
        val schemeName = SchemeName.of(query.schemeName)
        val identifier = IdentifierValue.of(query.identifier)

        transactionRunner.inTransaction {
            if (!tenantRepository.exists(tenantId)) {
                throw NotFoundException("tenant not found: ${tenantId.value}")
            }
            val scheme = identifierSchemeRepository.findByName(tenantId, entityType, schemeName)
                ?: return@inTransaction null
            entityIdentifierRepository.findByValue(tenantId, scheme.id, identifier)
                ?.toResult(scheme)
        }
    }

    private inline fun <T> mapValidation(block: () -> T): T =
        try {
            block()
        } catch (exception: IllegalArgumentException) {
            throw ValidationException(exception.message ?: "invalid catalog query", cause = exception)
        }
}
