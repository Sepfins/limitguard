package io.github.sepfins.limitguard.domain.policy

import io.github.sepfins.limitguard.domain.common.Money
import java.time.Instant

sealed interface PolicyAction {
    data class SetEffectiveAt(val effectiveAt: Instant) : PolicyAction

    data class CapRequestedAmount(val maximumAmount: Money) : PolicyAction
}
