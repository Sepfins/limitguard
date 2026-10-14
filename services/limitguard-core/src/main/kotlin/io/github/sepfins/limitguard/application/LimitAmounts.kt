package io.github.sepfins.limitguard.application

import io.github.sepfins.limitguard.api.LimitAmount as ApiLimitAmount
import io.github.sepfins.limitguard.domain.common.CurrencyCode
import io.github.sepfins.limitguard.domain.common.Money
import io.github.sepfins.limitguard.domain.limit.LimitAmount
import io.github.sepfins.limitguard.domain.limit.LimitRule

internal fun ApiLimitAmount.toDomain(currency: CurrencyCode): LimitAmount =
    when (this) {
        is ApiLimitAmount.Bounded -> LimitAmount.Bounded(Money.positive(value, currency))
        ApiLimitAmount.Unlimited -> LimitAmount.Unlimited(currency)
    }

internal fun LimitAmount.toApi(): ApiLimitAmount =
    when (this) {
        is LimitAmount.Bounded -> ApiLimitAmount.Bounded(money.amount)
        is LimitAmount.Unlimited -> ApiLimitAmount.Unlimited
    }

/** The cap an amount enforces, or null when it never denies. */
internal val LimitAmount.boundedMoney: Money?
    get() = (this as? LimitAmount.Bounded)?.money

/** The cap a bounded rule enforces. Unlimited rules never deny, so callers filter them out first. */
internal val LimitRule.cap: Money
    get() = requireNotNull(amount.boundedMoney) { "unlimited limit rule has no cap: ${id.value}" }

internal val LimitRule.isBounded: Boolean
    get() = amount is LimitAmount.Bounded
