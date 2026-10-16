package io.github.sepfins.limitguard.application.change

import io.github.sepfins.limitguard.api.commands.LimitRuleChangeOperation
import io.github.sepfins.limitguard.api.commands.RequestLimitRuleChangeCommand
import io.github.sepfins.limitguard.api.errors.ConflictException
import io.github.sepfins.limitguard.api.errors.NotFoundException
import io.github.sepfins.limitguard.application.toDomain
import io.github.sepfins.limitguard.domain.change.LimitRuleChangeType
import io.github.sepfins.limitguard.domain.change.ResolvedLimitChangeFacts
import io.github.sepfins.limitguard.domain.common.EffectiveWindow
import io.github.sepfins.limitguard.domain.common.LimitRuleId
import io.github.sepfins.limitguard.domain.common.PartyProfileId
import io.github.sepfins.limitguard.domain.common.TenantId
import io.github.sepfins.limitguard.domain.common.Version
import io.github.sepfins.limitguard.domain.limit.LimitAmount
import io.github.sepfins.limitguard.domain.limit.LimitConfiguration
import io.github.sepfins.limitguard.domain.limit.LimitRule
import io.github.sepfins.limitguard.domain.limit.LimitRuleName
import java.time.Instant

internal class LimitRuleCandidateFactory {
    fun changeType(operation: LimitRuleChangeOperation, currentRule: LimitRule?): LimitRuleChangeType =
        when (operation) {
            is LimitRuleChangeOperation.SetAmount ->
                if (currentRule == null) LimitRuleChangeType.CREATE else LimitRuleChangeType.UPDATE
            LimitRuleChangeOperation.Deactivate -> LimitRuleChangeType.DEACTIVATE
            LimitRuleChangeOperation.Reactivate -> LimitRuleChangeType.REACTIVATE
            LimitRuleChangeOperation.Delete -> LimitRuleChangeType.DELETE
        }

    fun createCandidate(
        command: RequestLimitRuleChangeCommand,
        facts: ResolvedLimitChangeFacts,
        newRuleId: () -> LimitRuleId,
        now: Instant,
    ): LimitRule {
        val currentRule = facts.currentRule
        val candidate = when (val operation = command.operation) {
            is LimitRuleChangeOperation.SetAmount -> {
                val amount = operation.amount.toDomain(facts.configuration.currency)
                currentRule?.copy(amount = amount, version = currentRule.version.next(), updatedAt = now)
                    ?: grant(command, facts.configuration, amount, newRuleId(), now)
            }
            LimitRuleChangeOperation.Deactivate -> {
                val rule = requireRule(currentRule)
                if (!rule.isActive) throw ConflictException("limit rule is already inactive: ${rule.id.value}")
                rule.copy(isActive = false, version = rule.version.next(), updatedAt = now)
            }
            LimitRuleChangeOperation.Reactivate -> {
                val rule = requireRule(currentRule)
                if (rule.isActive) throw ConflictException("limit rule is already active: ${rule.id.value}")
                require(rule.effectiveWindow.contains(command.effectiveAt ?: now)) {
                    "cannot reactivate a limit rule outside its effective window"
                }
                rule.copy(isActive = true, version = rule.version.next(), updatedAt = now)
            }
            LimitRuleChangeOperation.Delete -> {
                val rule = requireRule(currentRule)
                rule.copy(isActive = false, version = rule.version.next(), updatedAt = now)
            }
        }

        if (candidate.isActive) {
            val applicationTime = command.effectiveAt ?: now
            require(candidate.effectiveWindow.endsAt == null || candidate.effectiveWindow.endsAt > applicationTime) {
                "active limit rule must not end before it is applied"
            }
        }
        if (changeType(command.operation, currentRule) in CONFIGURATION_VALIDATED_CHANGES) {
            candidate.validateAgainst(facts.configuration, facts.ancestorConfigurations)
        }
        return candidate
    }

    /**
     * Kind, scopes, priority, usage mode, and end come from the configuration; the rule adds only
     * an amount. The rule keeps its own window until rules lose it altogether.
     */
    private fun grant(
        command: RequestLimitRuleChangeCommand,
        configuration: LimitConfiguration,
        amount: LimitAmount,
        id: LimitRuleId,
        now: Instant,
    ): LimitRule =
        LimitRule(
            id = id,
            tenantId = TenantId.of(command.tenantId),
            ownerPartyProfileId = PartyProfileId.of(command.target.ownerPartyProfileId),
            name = LimitRuleName.of(configuration.name.value),
            description = null,
            limitKind = configuration.limitKind,
            amount = amount,
            mandateScope = command.toMandateScope(),
            configurationId = configuration.id,
            channelScope = configuration.channelScope,
            operationScope = configuration.operationScope,
            usageConsumptionMode = configuration.usageConsumptionMode,
            priority = configuration.priority,
            isActive = true,
            version = Version.INITIAL,
            effectiveWindow = EffectiveWindow(startsAt = now, endsAt = configuration.effectiveWindow.endsAt),
            createdAt = now,
            updatedAt = now,
        )

    private fun requireRule(rule: LimitRule?): LimitRule =
        rule ?: throw NotFoundException("no limit rule exists for the target")

    private companion object {
        val CONFIGURATION_VALIDATED_CHANGES = setOf(
            LimitRuleChangeType.CREATE,
            LimitRuleChangeType.UPDATE,
            LimitRuleChangeType.REACTIVATE,
        )
    }
}
