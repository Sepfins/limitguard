package io.github.sepfins.limitguard.application.evaluation

import io.github.sepfins.limitguard.domain.common.MandateId
import io.github.sepfins.limitguard.domain.common.PartyProfileId

internal data class ResolvedFundingAuthority(
    val ownerPartyProfileId: PartyProfileId,
    val directOwnership: Boolean,
    val mandateIds: Set<MandateId>,
)
