package io.github.sepfins.limitguard.domain.change

import io.github.sepfins.limitguard.domain.common.BankWorkerId
import io.github.sepfins.limitguard.domain.common.PartyProfileId

sealed interface ChangeActor {
    data object System : ChangeActor

    data class PartyProfileActor(val partyProfileId: PartyProfileId) : ChangeActor

    data class BankWorkerActor(val bankWorkerId: BankWorkerId) : ChangeActor
}
