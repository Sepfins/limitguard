package io.github.sepfins.limitguard.api

import io.github.sepfins.limitguard.api.queries.FindChannelByCodeQuery
import io.github.sepfins.limitguard.api.queries.FindFundingSourceByExternalReferenceQuery
import io.github.sepfins.limitguard.api.queries.FindFundingSourceProductByCodeQuery
import io.github.sepfins.limitguard.api.queries.FindLimitConfigurationByCodeQuery
import io.github.sepfins.limitguard.api.queries.FindOperationTypesByCodeQuery
import io.github.sepfins.limitguard.api.queries.FindTenantByNameQuery
import io.github.sepfins.limitguard.api.queries.GetCurrentUsageQuery
import io.github.sepfins.limitguard.api.queries.GetLimitChangeQuery
import io.github.sepfins.limitguard.api.queries.GetLimitConfigurationQuery
import io.github.sepfins.limitguard.api.queries.GetLimitRuleQuery
import io.github.sepfins.limitguard.api.queries.GetOperationCapacityQuery
import io.github.sepfins.limitguard.api.queries.GetTransactionHistoryQuery
import io.github.sepfins.limitguard.api.results.ChannelResult
import io.github.sepfins.limitguard.api.results.CurrentUsageResult
import io.github.sepfins.limitguard.api.results.FundingSourceProductResult
import io.github.sepfins.limitguard.api.results.FundingSourceResult
import io.github.sepfins.limitguard.api.results.LimitChangeDetailsResult
import io.github.sepfins.limitguard.api.results.LimitRuleResult
import io.github.sepfins.limitguard.api.results.LimitConfigurationResult
import io.github.sepfins.limitguard.api.results.OperationCapacityResult
import io.github.sepfins.limitguard.api.results.OperationTypeResult
import io.github.sepfins.limitguard.api.results.TenantResult
import io.github.sepfins.limitguard.api.results.TransactionHistoryResult

interface LimitInquiryService {
    fun getLimitChange(query: GetLimitChangeQuery): LimitChangeDetailsResult

    fun getLimitRule(query: GetLimitRuleQuery): LimitRuleResult

    /** The configuration a rule is an amount for, which supplies its kind, period, currency, and scopes. */
    fun getLimitConfiguration(query: GetLimitConfigurationQuery): LimitConfigurationResult

    fun getTransactionHistory(query: GetTransactionHistoryQuery): TransactionHistoryResult

    fun getCurrentUsage(query: GetCurrentUsageQuery): CurrentUsageResult

    fun getOperationCapacity(query: GetOperationCapacityQuery): OperationCapacityResult

    fun findTenantByName(query: FindTenantByNameQuery): TenantResult?

    fun findChannelByCode(query: FindChannelByCodeQuery): ChannelResult?

    fun findOperationTypesByCode(query: FindOperationTypesByCodeQuery): List<OperationTypeResult>

    fun findFundingSourceProductByCode(
        query: FindFundingSourceProductByCodeQuery,
    ): FundingSourceProductResult?

    fun findFundingSourceByExternalReference(
        query: FindFundingSourceByExternalReferenceQuery,
    ): FundingSourceResult?

    fun findLimitConfigurationByCode(
        query: FindLimitConfigurationByCodeQuery,
    ): LimitConfigurationResult?
}
