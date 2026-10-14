package io.github.sepfins.limitguard.application.change

import io.github.sepfins.limitguard.api.commands.LimitRuleDraft
import io.github.sepfins.limitguard.application.boundedMoney
import io.github.sepfins.limitguard.domain.limit.LimitAmount
import io.github.sepfins.limitguard.domain.limit.LimitPriority
import io.github.sepfins.limitguard.domain.limit.LimitRuleName

internal fun LimitRuleDraft.normalizedIntent(defaults: Map<*, *>): Map<String, Any?> {
    toLimitKind()
    toMandateScope()
    toChannelScope()
    val limitAmount = toLimitAmount()
    return mapOf(
        "name" to LimitRuleName.of(name).value,
        "description" to description,
        "limitKind" to limitKind.name,
        "period" to period?.name,
        "amount" to limitAmount.boundedMoney?.amount?.toString(),
        "amountUnlimited" to (limitAmount is LimitAmount.Unlimited),
        "currency" to limitAmount.currency.value,
        "mandateScope" to mandateScope.name,
        "mandateId" to mandateId?.toString(),
        "configurationId" to configurationId.toString(),
        "channelScope" to channelScope.name,
        "channelId" to channelId?.toString(),
        "channelGroupId" to channelGroupId?.toString(),
        "operationTypeId" to operationTypeId?.toString(),
        "usageConsumptionMode" to usageConsumptionMode.name,
        "priority" to LimitPriority.of(priority).value,
        "startsAt" to (startsAt?.toString() ?: defaults["startsAt"]),
        "endsAt" to endsAt?.toString(),
        "metadata" to metadata,
    )
}
