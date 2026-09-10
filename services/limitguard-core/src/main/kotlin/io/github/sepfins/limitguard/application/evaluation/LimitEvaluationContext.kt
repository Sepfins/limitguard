package io.github.sepfins.limitguard.application.evaluation

import io.github.sepfins.limitguard.domain.common.ChannelId
import io.github.sepfins.limitguard.domain.common.FundingSourceId
import io.github.sepfins.limitguard.domain.common.OperationTypeId
import io.github.sepfins.limitguard.domain.common.PartyProfileId
import io.github.sepfins.limitguard.domain.common.TenantId
import io.github.sepfins.limitguard.domain.transaction.PaymentTransaction

internal data class LimitEvaluationContext(
    val tenantId: TenantId,
    val actingPartyProfileId: PartyProfileId,
    val fundingSourceId: FundingSourceId,
    val operationTypeId: OperationTypeId,
    val channelId: ChannelId,
)

internal fun PaymentTransaction.toLimitEvaluationContext(): LimitEvaluationContext =
    LimitEvaluationContext(
        tenantId = tenantId,
        actingPartyProfileId = actingPartyProfileId,
        fundingSourceId = fundingSourceId,
        operationTypeId = operationTypeId,
        channelId = channelId,
    )
