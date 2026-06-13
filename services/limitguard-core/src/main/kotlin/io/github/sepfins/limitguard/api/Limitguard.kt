package io.github.sepfins.limitguard.api

interface Limitguard {
    val tenantCatalogService: TenantCatalogService

    val limitChangeService: LimitChangeService

    val limitEvaluationService: LimitEvaluationService
}
