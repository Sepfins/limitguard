package io.github.sepfins.limitguard.domain.mandate

import io.github.sepfins.limitguard.domain.common.MandateId

sealed interface MandateScope {
    data object AllOwnerMandates : MandateScope

    data class MandateSpecific(val mandateId: MandateId) : MandateScope
}
