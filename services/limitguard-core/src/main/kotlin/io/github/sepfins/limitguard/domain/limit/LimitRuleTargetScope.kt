package io.github.sepfins.limitguard.domain.limit

import io.github.sepfins.limitguard.domain.common.CurrencyCode
import io.github.sepfins.limitguard.domain.common.PartyProfileId
import io.github.sepfins.limitguard.domain.mandate.MandateScope

data class LimitRuleTargetScope(
    val owner: PartyProfileId,
    val mandateScope: MandateScope,
    val channelScope: LimitChannelScope,
    val operationScope: LimitOperationScope,
    val currency: CurrencyCode,
    val kind: LimitKind
)