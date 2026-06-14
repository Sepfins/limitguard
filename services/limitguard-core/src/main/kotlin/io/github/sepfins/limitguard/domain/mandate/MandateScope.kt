package io.github.sepfins.limitguard.domain.mandate

import io.github.sepfins.limitguard.domain.common.MandateId

sealed interface MandateScope {
    data object Global : MandateScope

    data class MandateSpecific(val mandateId: MandateId) : MandateScope
}
