package io.github.sepfins.limitguard.application.change

import io.github.sepfins.limitguard.api.commands.RequestLimitRuleChangeCommand
import io.github.sepfins.limitguard.domain.common.CurrencyCode
import io.github.sepfins.limitguard.domain.common.EffectiveWindow
import io.github.sepfins.limitguard.domain.common.LimitConfigurationId
import io.github.sepfins.limitguard.domain.common.LimitRuleId
import io.github.sepfins.limitguard.domain.common.Money
import io.github.sepfins.limitguard.domain.common.PartyProfileId
import io.github.sepfins.limitguard.domain.common.TenantId
import io.github.sepfins.limitguard.domain.common.Version
import io.github.sepfins.limitguard.domain.limit.LimitConfiguration
import io.github.sepfins.limitguard.domain.limit.LimitPriority
import io.github.sepfins.limitguard.domain.limit.LimitRule
import io.github.sepfins.limitguard.domain.limit.LimitRuleName
import io.github.sepfins.limitguard.domain.limit.LimitUsageConsumptionMode
import io.github.sepfins.limitguard.port.ids.IdGenerator
import io.github.sepfins.limitguard.domain.common.Metadata
import java.time.Instant

internal class LimitRuleFactory(
    private val idGenerator: IdGenerator,
) {
    fun createForCreate(
        command: RequestLimitRuleChangeCommand,
        now: Instant
    ): LimitRule {
        val draft = command.requestedRule

        return LimitRule(
            id = LimitRuleId.of(idGenerator.newId()),
            tenantId = TenantId.of(command.tenantId),
            ownerPartyProfileId = PartyProfileId.of(command.targetOwnerPartyProfileId),
            name = LimitRuleName.of(draft.name),
            description = draft.description,
            limitKind = draft.toLimitKind(),
            amount = Money.positive(
                draft.amount,
                CurrencyCode.of(draft.currency),
            ),
            mandateScope = draft.toMandateScope(),
            configurationId = draft.configurationId?.let { LimitConfigurationId.of(it) },
            channelScope = draft.toChannelScope(),
            operationScope = draft.toOperationScope(),
            usageConsumptionMode = LimitUsageConsumptionMode.valueOf(draft.usageConsumptionMode.name),
            priority = LimitPriority.of(draft.priority),
            isActive = true,
            version = Version.INITIAL,
            effectiveWindow = EffectiveWindow(
                startsAt = draft.startsAt ?: now,
                endsAt = draft.endsAt,
            ),
            metadata = Metadata(draft.metadata),
            createdAt = now,
            updatedAt = now,
        )
    }
}