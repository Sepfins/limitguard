package io.github.sepfins.limitguard.domain.change

import io.github.sepfins.limitguard.domain.common.BankWorkerId
import io.github.sepfins.limitguard.domain.common.ChannelId
import io.github.sepfins.limitguard.domain.common.PartyProfileId

interface RequestActorContext {
    data object System: RequestActorContext

    data class PartyProfile(val requesterPartyProfileId: PartyProfileId, val requesterChannelId: ChannelId): RequestActorContext
    data class BankWorker(val bankWorkerId: BankWorkerId, val requesterChannelId: ChannelId): RequestActorContext
}