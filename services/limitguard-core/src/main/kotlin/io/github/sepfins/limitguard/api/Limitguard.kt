package io.github.sepfins.limitguard.api

interface Limitguard {
    val tenantCatalogService: TenantCatalogService

    val tenantCatalogQueryService: TenantCatalogQueryService

    val limitChangeService: LimitChangeService

    val limitEvaluationService: LimitEvaluationService

    val limitInquiryService: LimitInquiryService
}
