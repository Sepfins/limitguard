package io.github.sepfins.limitguard.application.usage

import io.github.sepfins.limitguard.port.ids.IdGenerator
import io.github.sepfins.limitguard.port.persistence.TransactionEventRepository
import io.github.sepfins.limitguard.port.persistence.UsageBucketRepository
import io.github.sepfins.limitguard.port.persistence.UsageEventRepository

class UsageReleaseService(
    private val idGenerator: IdGenerator,
    private val transactionEventRepository: TransactionEventRepository,
    private val usageBucketRepository: UsageBucketRepository,
    private val usageEventRepository: UsageEventRepository,
) {

}
