package io.github.sepfins.limitguard.application.inquiry

import io.github.sepfins.limitguard.api.AuthorizationDecision
import io.github.sepfins.limitguard.api.LimitChangeActorType
import io.github.sepfins.limitguard.api.LimitInquiryService
import io.github.sepfins.limitguard.api.LimitPeriod as ApiLimitPeriod
import io.github.sepfins.limitguard.api.LimitRuleChangeEventType as ApiLimitRuleChangeEventType
import io.github.sepfins.limitguard.api.LimitRuleChangeStatus as ApiLimitRuleChangeStatus
import io.github.sepfins.limitguard.api.LimitRuleRevisionEventType as ApiLimitRuleRevisionEventType
import io.github.sepfins.limitguard.api.TransactionEventType as ApiTransactionEventType
import io.github.sepfins.limitguard.api.TransactionStatus as ApiTransactionStatus
import io.github.sepfins.limitguard.api.errors.NotFoundException
import io.github.sepfins.limitguard.api.errors.ValidationException
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
import io.github.sepfins.limitguard.api.results.AuthorizationDecisionSummaryResult
import io.github.sepfins.limitguard.api.results.ChannelResult
import io.github.sepfins.limitguard.api.results.CurrentUsageResult
import io.github.sepfins.limitguard.api.results.FundingSourceProductResult
import io.github.sepfins.limitguard.api.results.FundingSourceResult
import io.github.sepfins.limitguard.api.results.LimitChangeActorResult
import io.github.sepfins.limitguard.api.results.LimitChangeDetailsResult
import io.github.sepfins.limitguard.api.results.LimitChangeEventResult
import io.github.sepfins.limitguard.api.results.LimitConfigurationResult
import io.github.sepfins.limitguard.api.results.LimitRuleResult
import io.github.sepfins.limitguard.api.results.LimitRuleRevisionResult
import io.github.sepfins.limitguard.api.results.OperationCapacityResult
import io.github.sepfins.limitguard.api.results.OperationTypeResult
import io.github.sepfins.limitguard.api.results.PaymentTransactionResult
import io.github.sepfins.limitguard.api.results.TenantResult
import io.github.sepfins.limitguard.api.results.TransactionHistoryEventResult
import io.github.sepfins.limitguard.api.results.TransactionHistoryResult
import io.github.sepfins.limitguard.application.applicability.ApplicableLimitRule
import io.github.sepfins.limitguard.application.applicability.LimitApplicabilityResolver
import io.github.sepfins.limitguard.application.applicability.LimitEvaluationContext
import io.github.sepfins.limitguard.application.cap
import io.github.sepfins.limitguard.application.catalog.toResult
import io.github.sepfins.limitguard.application.isBounded
import io.github.sepfins.limitguard.application.toApi
import io.github.sepfins.limitguard.application.change.toResult
import io.github.sepfins.limitguard.application.usage.AvailableLimitCapacityResolver
import io.github.sepfins.limitguard.domain.change.ChangeActor
import io.github.sepfins.limitguard.domain.change.LimitRuleChangeEvent
import io.github.sepfins.limitguard.domain.change.LimitRuleRevision
import io.github.sepfins.limitguard.domain.channel.ChannelCode
import io.github.sepfins.limitguard.domain.common.ChannelId
import io.github.sepfins.limitguard.domain.common.CurrencyCode
import io.github.sepfins.limitguard.domain.common.ExternalReference
import io.github.sepfins.limitguard.domain.common.FundingSourceId
import io.github.sepfins.limitguard.domain.common.LimitChangeRequestId
import io.github.sepfins.limitguard.domain.common.LimitConfigurationId
import io.github.sepfins.limitguard.domain.common.LimitRuleId
import io.github.sepfins.limitguard.domain.common.OperationTypeId
import io.github.sepfins.limitguard.domain.common.PartyProfileId
import io.github.sepfins.limitguard.domain.common.PaymentTransactionId
import io.github.sepfins.limitguard.domain.common.TenantId
import io.github.sepfins.limitguard.domain.funding.FundingSourceProductCode
import io.github.sepfins.limitguard.domain.limit.LimitConfigurationCode
import io.github.sepfins.limitguard.domain.limit.LimitKind
import io.github.sepfins.limitguard.domain.limit.LimitRule
import io.github.sepfins.limitguard.domain.mandate.MandateScope
import io.github.sepfins.limitguard.domain.operation.OperationTypeCode
import io.github.sepfins.limitguard.domain.tenant.TenantName
import io.github.sepfins.limitguard.domain.transaction.AuthorizationDecisionRecord
import io.github.sepfins.limitguard.domain.transaction.PaymentTransaction
import io.github.sepfins.limitguard.domain.transaction.TransactionEvent
import io.github.sepfins.limitguard.port.persistence.AuthorizationDecisionRepository
import io.github.sepfins.limitguard.port.persistence.ChannelRepository
import io.github.sepfins.limitguard.port.persistence.FundingSourceProductRepository
import io.github.sepfins.limitguard.port.persistence.FundingSourceRepository
import io.github.sepfins.limitguard.port.persistence.LimitChangeRequestRepository
import io.github.sepfins.limitguard.port.persistence.LimitConfigurationRepository
import io.github.sepfins.limitguard.port.persistence.LimitRuleChangeEventRepository
import io.github.sepfins.limitguard.port.persistence.LimitRuleRepository
import io.github.sepfins.limitguard.port.persistence.LimitRuleRevisionRepository
import io.github.sepfins.limitguard.port.persistence.OperationTypeRepository
import io.github.sepfins.limitguard.port.persistence.PartyProfileRepository
import io.github.sepfins.limitguard.port.persistence.TenantRepository
import io.github.sepfins.limitguard.port.persistence.TransactionEventRepository
import io.github.sepfins.limitguard.port.persistence.TransactionRepository
import io.github.sepfins.limitguard.port.time.ClockProvider
import io.github.sepfins.limitguard.port.transactions.TransactionRunner

internal class DefaultLimitInquiryService(
    private val tenantRepository: TenantRepository,
    private val channelRepository: ChannelRepository,
    private val operationTypeRepository: OperationTypeRepository,
    private val fundingSourceProductRepository: FundingSourceProductRepository,
    private val fundingSourceRepository: FundingSourceRepository,
    private val limitConfigurationRepository: LimitConfigurationRepository,
    private val partyProfileRepository: PartyProfileRepository,
    private val limitChangeRequestRepository: LimitChangeRequestRepository,
    private val limitChangeEventRepository: LimitRuleChangeEventRepository,
    private val limitRuleRevisionRepository: LimitRuleRevisionRepository,
    private val limitRuleRepository: LimitRuleRepository,
    private val transactionRepository: TransactionRepository,
    private val transactionEventRepository: TransactionEventRepository,
    private val authorizationDecisionRepository: AuthorizationDecisionRepository,
    limitApplicabilityResolver: LimitApplicabilityResolver,
    private val capacityResolver: AvailableLimitCapacityResolver,
    private val clockProvider: ClockProvider,
    private val transactionRunner: TransactionRunner,
) : LimitInquiryService {
    private val operationCapacityReader = OperationCapacityReader(
        limitApplicabilityResolver = limitApplicabilityResolver,
        capacityResolver = capacityResolver,
    )

    override fun getLimitChange(query: GetLimitChangeQuery): LimitChangeDetailsResult = mapValidation {
        val tenantId = TenantId.of(query.tenantId)
        val requestId = LimitChangeRequestId.of(query.changeRequestId)

        transactionRunner.inTransaction {
            val request = limitChangeRequestRepository.findById(tenantId, requestId)
                ?: throw NotFoundException("limit change request not found: ${requestId.value}")

            LimitChangeDetailsResult(
                request = request.toResult(),
                events = limitChangeEventRepository.findByChangeRequest(tenantId, requestId)
                    .map { it.toResult() },
                revisions = limitRuleRevisionRepository.findByChangeRequest(tenantId, requestId)
                    .map { it.toResult() },
            )
        }
    }

    override fun getLimitRule(query: GetLimitRuleQuery): LimitRuleResult = mapValidation {
        val tenantId = requireTenant(query.tenantId)
        val ruleId = LimitRuleId.of(query.limitRuleId)

        transactionRunner.inTransaction {
            limitRuleRepository.findById(tenantId, ruleId)?.toResult()
                ?: throw NotFoundException("limit rule not found: ${ruleId.value}")
        }
    }

    override fun getLimitConfiguration(query: GetLimitConfigurationQuery): LimitConfigurationResult = mapValidation {
        val tenantId = requireTenant(query.tenantId)
        val configurationId = LimitConfigurationId.of(query.limitConfigurationId)

        limitConfigurationRepository.findById(tenantId, configurationId)?.toResult()
            ?: throw NotFoundException("limit configuration not found: ${configurationId.value}")
    }

    override fun getTransactionHistory(query: GetTransactionHistoryQuery): TransactionHistoryResult = mapValidation {
        val tenantId = TenantId.of(query.tenantId)
        val transactionId = PaymentTransactionId.of(query.paymentTransactionId)

        transactionRunner.inTransaction {
            val transaction = transactionRepository.findById(tenantId, transactionId)
                ?: throw NotFoundException("payment transaction not found: ${transactionId.value}")

            TransactionHistoryResult(
                transaction = transaction.toResult(),
                authorizationDecision = authorizationDecisionRepository.findByTransaction(tenantId, transactionId)
                    ?.toResult(),
                events = transactionEventRepository.findByTransaction(tenantId, transactionId)
                    .map { it.toResult() },
            )
        }
    }

    override fun getCurrentUsage(query: GetCurrentUsageQuery): CurrentUsageResult = mapValidation {
        val tenantId = TenantId.of(query.tenantId)
        val ruleId = LimitRuleId.of(query.limitRuleId)
        val assigneeId = PartyProfileId.of(query.assigneePartyProfileId)
        val calculatedAt = query.at ?: clockProvider.now()

        transactionRunner.inTransaction {
            val rule = limitRuleRepository.findById(tenantId, ruleId)
                ?: throw NotFoundException("limit rule not found: ${ruleId.value}")
            if (partyProfileRepository.findById(tenantId, assigneeId) == null) {
                throw NotFoundException("party profile not found: ${assigneeId.value}")
            }
            val configuration = limitConfigurationRepository.findById(tenantId, rule.configurationId)
                ?: throw NotFoundException("limit configuration not found: ${rule.configurationId.value}")
            val periodicKind = configuration.limitKind as? LimitKind.Periodic
                ?: throw ValidationException("current usage is only available for periodic limit rules")
            if (!rule.isBounded) throw ValidationException("current usage is only available for bounded limit rules")
            val capacity = capacityResolver
                .read(tenantId, assigneeId, calculatedAt, listOf(ApplicableLimitRule(rule, configuration)))
                .single()
            val window = checkNotNull(capacity.window)

            CurrentUsageResult(
                tenantId = tenantId.value,
                limitRuleId = ruleId.value,
                assigneePartyProfileId = assigneeId.value,
                usageBucketId = capacity.bucket?.id?.value,
                period = ApiLimitPeriod.valueOf(periodicKind.period.name),
                periodStartsAt = window.periodStartsAt,
                periodEndsAt = window.periodEndsAt,
                limitAmount = rule.cap.amount,
                reservedAmount = checkNotNull(capacity.reserved).amount,
                consumedAmount = checkNotNull(capacity.consumed).amount,
                remainingAmount = checkNotNull(capacity.remaining).amount,
                currency = rule.amount.currency.value,
                calculatedAt = calculatedAt,
                updatedAt = capacity.bucket?.updatedAt,
            )
        }
    }

    override fun getOperationCapacity(query: GetOperationCapacityQuery): OperationCapacityResult = mapValidation {
        val context = LimitEvaluationContext(
            tenantId = TenantId.of(query.tenantId),
            actingPartyProfileId = PartyProfileId.of(query.actingPartyProfileId),
            fundingSourceId = FundingSourceId.of(query.fundingSourceId),
            operationTypeId = OperationTypeId.of(query.operationTypeId),
            channelId = ChannelId.of(query.channelId),
        )
        val currency = CurrencyCode.of(query.currency)
        val calculatedAt = query.at ?: clockProvider.now()

        transactionRunner.inTransaction {
            operationCapacityReader.read(context, currency, calculatedAt)
        }
    }

    override fun findTenantByName(query: FindTenantByNameQuery): TenantResult? = mapValidation {
        tenantRepository.findByName(TenantName.of(query.name))?.toResult()
    }

    override fun findChannelByCode(query: FindChannelByCodeQuery): ChannelResult? = mapValidation {
        val tenantId = requireTenant(query.tenantId)
        channelRepository.findByCode(tenantId, ChannelCode.of(query.code))?.toResult()
    }

    override fun findOperationTypesByCode(
        query: FindOperationTypesByCodeQuery,
    ): List<OperationTypeResult> = mapValidation {
        val tenantId = requireTenant(query.tenantId)
        operationTypeRepository.findByCodeVisibleToTenant(tenantId, OperationTypeCode.of(query.code))
            .map { it.toResult() }
    }

    override fun findFundingSourceProductByCode(
        query: FindFundingSourceProductByCodeQuery,
    ): FundingSourceProductResult? = mapValidation {
        val tenantId = requireTenant(query.tenantId)
        fundingSourceProductRepository.findByCode(tenantId, FundingSourceProductCode.of(query.code))
            ?.toResult()
    }

    override fun findFundingSourceByExternalReference(
        query: FindFundingSourceByExternalReferenceQuery,
    ): FundingSourceResult? = mapValidation {
        val tenantId = requireTenant(query.tenantId)
        fundingSourceRepository.findByExternalReference(
            tenantId,
            ExternalReference.of(query.externalReference),
        )?.toResult()
    }

    override fun findLimitConfigurationByCode(
        query: FindLimitConfigurationByCodeQuery,
    ): LimitConfigurationResult? = mapValidation {
        val tenantId = requireTenant(query.tenantId)
        limitConfigurationRepository.findByCode(tenantId, LimitConfigurationCode.of(query.code))
            ?.toResult()
    }

    private fun requireTenant(rawTenantId: java.util.UUID): TenantId {
        val tenantId = TenantId.of(rawTenantId)
        if (!tenantRepository.exists(tenantId)) {
            throw NotFoundException("tenant not found: ${tenantId.value}")
        }
        return tenantId
    }

    private fun LimitRuleChangeEvent.toResult(): LimitChangeEventResult =
        LimitChangeEventResult(
            id = id.value,
            eventType = ApiLimitRuleChangeEventType.valueOf(eventType.name),
            actor = actor.toResult(),
            policyId = policyId?.value,
            beforeStatus = beforeStatus?.let { ApiLimitRuleChangeStatus.valueOf(it.name) },
            afterStatus = afterStatus?.let { ApiLimitRuleChangeStatus.valueOf(it.name) },
            payload = eventPayload.value,
            message = message,
            createdAt = requireNotNull(createdAt) {
                "LimitRuleChangeEvent.createdAt is required when mapping inquiry results"
            },
        )

    private fun LimitRuleRevision.toResult(): LimitRuleRevisionResult =
        LimitRuleRevisionResult(
            id = id.value,
            limitRuleId = limitRuleId.value,
            revisionNumber = revisionNumber.value,
            eventType = ApiLimitRuleRevisionEventType.valueOf(revisionEventType.name),
            actor = actor.toResult(),
            beforeAmount = beforeAmount?.toApi(),
            afterAmount = afterAmount.toApi(),
            effectiveAt = effectiveAt,
            metadata = metadata.value,
            createdAt = requireNotNull(createdAt) {
                "LimitRuleRevision.createdAt is required when mapping inquiry results"
            },
        )

    private fun ChangeActor.toResult(): LimitChangeActorResult =
        when (this) {
            ChangeActor.System -> LimitChangeActorResult(LimitChangeActorType.SYSTEM)
            is ChangeActor.PartyProfileActor -> LimitChangeActorResult(
                actorType = LimitChangeActorType.PARTY_PROFILE,
                partyProfileId = partyProfileId.value,
            )
        }

    private fun PaymentTransaction.toResult(): PaymentTransactionResult =
        PaymentTransactionResult(
            id = id.value,
            tenantId = tenantId.value,
            actingPartyProfileId = actingPartyProfileId.value,
            fundingSourceId = fundingSourceId.value,
            operationTypeId = operationTypeId.value,
            channelId = channelId.value,
            authorizedAmount = authorizedAmount.amount,
            capturedAmount = capturedAmount.amount,
            uncapturedAmount = uncapturedAmount.amount,
            currency = authorizedAmount.currency.value,
            status = ApiTransactionStatus.valueOf(status.name),
            expiresAt = expiresAt,
            metadata = metadata.value,
            createdAt = requireNotNull(createdAt) {
                "PaymentTransaction.createdAt is required when mapping inquiry results"
            },
        )

    private fun AuthorizationDecisionRecord.toResult(): AuthorizationDecisionSummaryResult =
        AuthorizationDecisionSummaryResult(
            id = id.value,
            decision = AuthorizationDecision.valueOf(decision.name),
            requestedAmount = requestedAmount.amount,
            currency = requestedAmount.currency.value,
            expiresAt = expiresAt,
            createdAt = requireNotNull(createdAt) {
                "AuthorizationDecisionRecord.createdAt is required when mapping inquiry results"
            },
        )

    private fun LimitRule.toResult(): LimitRuleResult =
        LimitRuleResult(
            id = id.value,
            tenantId = tenantId.value,
            ownerPartyProfileId = ownerPartyProfileId.value,
            configurationId = configurationId.value,
            mandateId = (mandateScope as? MandateScope.MandateSpecific)?.mandateId?.value,
            amount = amount.toApi(),
            isActive = isActive,
            version = version.value,
            metadata = metadata.value,
            createdAt = createdAt,
            updatedAt = updatedAt,
        )

    private fun TransactionEvent.toResult(): TransactionHistoryEventResult =
        TransactionHistoryEventResult(
            id = id.value,
            eventType = ApiTransactionEventType.valueOf(eventType.name),
            amount = amount?.amount,
            currency = amount?.currency?.value,
            reason = reason?.value,
            metadata = metadata.value,
            createdAt = requireNotNull(createdAt) {
                "TransactionEvent.createdAt is required when mapping inquiry results"
            },
        )

    private inline fun <T> mapValidation(block: () -> T): T =
        try {
            block()
        } catch (exception: IllegalArgumentException) {
            throw ValidationException(
                message = exception.message ?: "inquiry validation failed",
                cause = exception,
            )
        }
}
