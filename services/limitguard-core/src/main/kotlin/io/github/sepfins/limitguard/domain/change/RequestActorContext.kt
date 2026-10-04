package io.github.sepfins.limitguard.domain.change

import io.github.sepfins.limitguard.domain.common.BankWorkerId
import io.github.sepfins.limitguard.domain.common.ChannelId
import io.github.sepfins.limitguard.domain.common.PartyProfileId

sealed interface RequestActorContext {
    val requestChannelId: ChannelId?

    data class System(override val requestChannelId: ChannelId? = null) : RequestActorContext

    data class PartyProfile(
        val actorPartyProfileId: PartyProfileId,
        override val requestChannelId: ChannelId,
    ) : RequestActorContext

    data class BankWorker(
        val bankWorkerId: BankWorkerId,
        override val requestChannelId: ChannelId,
    ) : RequestActorContext
}
