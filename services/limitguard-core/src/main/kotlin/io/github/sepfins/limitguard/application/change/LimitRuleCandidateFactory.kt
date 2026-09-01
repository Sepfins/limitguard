package io.github.sepfins.limitguard.application.change

import io.github.sepfins.limitguard.api.LimitRuleChangeType
import io.github.sepfins.limitguard.api.commands.LimitRuleDraft
import io.github.sepfins.limitguard.api.commands.RequestLimitRuleChangeCommand
import io.github.sepfins.limitguard.api.errors.ConflictException
import io.github.sepfins.limitguard.domain.change.ResolvedLimitChangeFacts
import io.github.sepfins.limitguard.domain.common.CurrencyCode
import io.github.sepfins.limitguard.domain.common.EffectiveWindow
import io.github.sepfins.limitguard.domain.common.LimitConfigurationId
import io.github.sepfins.limitguard.domain.common.LimitRuleId
import io.github.sepfins.limitguard.domain.common.Metadata
import io.github.sepfins.limitguard.domain.common.Money
import io.github.sepfins.limitguard.domain.common.PartyProfileId
import io.github.sepfins.limitguard.domain.common.TenantId
import io.github.sepfins.limitguard.domain.common.Version
import io.github.sepfins.limitguard.domain.limit.LimitPriority
import io.github.sepfins.limitguard.domain.limit.LimitRule
import io.github.sepfins.limitguard.domain.limit.LimitRuleName
import io.github.sepfins.limitguard.domain.limit.LimitUsageConsumptionMode
import java.time.Instant

internal class LimitRuleCandidateFactory {
    fun createCandidate(
        command: RequestLimitRuleChangeCommand,
        facts: ResolvedLimitChangeFacts,
        futureRuleId: LimitRuleId?,
        now: Instant,
    ): LimitRule {
        val currentRule = facts.currentRule
        val candidate = when (command.changeType) {
            LimitRuleChangeType.CREATE -> fromDraft(
                command = command,
                draft = requireNotNull(command.requestedRule),
                id = requireNotNull(futureRuleId) { "futureRuleId is required for CREATE changes" },
                currentRule = null,
                isActive = true,
                version = Version.INITIAL,
                now = now,
            )
            LimitRuleChangeType.UPDATE -> fromDraft(
                command = command,
                draft = requireNotNull(command.requestedRule),
                id = requireNotNull(currentRule).id,
                currentRule = currentRule,
                isActive = currentRule.isActive,
                version = currentRule.version.next(),
                now = now,
            )
            LimitRuleChangeType.DEACTIVATE -> {
                requireNotNull(currentRule)
                if (!currentRule.isActive) {
                    throw ConflictException("limit rule is already inactive: ${currentRule.id.value}")
                }
                currentRule.copy(
                    isActive = false,
                    version = currentRule.version.next(),
                    updatedAt = now,
                )
            }
            LimitRuleChangeType.REACTIVATE -> {
                requireNotNull(currentRule)
                if (currentRule.isActive) {
                    throw ConflictException("limit rule is already active: ${currentRule.id.value}")
                }
                command.requestedRule?.let {
                    fromDraft(
                        command = command,
                        draft = it,
                        id = currentRule.id,
                        currentRule = currentRule,
                        isActive = true,
                        version = currentRule.version.next(),
                        now = now,
                    )
                } ?: currentRule.copy(
                    isActive = true,
                    version = currentRule.version.next(),
                    updatedAt = now,
                ).also {
                    val applicationTime = command.effectiveAt ?: now
                    require(it.effectiveWindow.contains(applicationTime)) {
                        "requestedRule is required to reactivate a rule outside its effective window"
                    }
                }
            }
            LimitRuleChangeType.DELETE -> {
                requireNotNull(currentRule)
                currentRule.copy(
                    isActive = false,
                    version = currentRule.version.next(),
                    updatedAt = now,
                )
            }
        }

        if (candidate.isActive) {
            val applicationTime = command.effectiveAt ?: now
            require(candidate.effectiveWindow.endsAt == null || candidate.effectiveWindow.endsAt > applicationTime) {
                "active limit rule must not end before it is applied"
            }
        }
        return validateCandidate(candidate, facts, command.changeType)
    }

    fun createApprovedCandidate(
        requestedCandidate: LimitRule,
        approvedRule: LimitRuleDraft,
        effectiveAt: Instant?,
        now: Instant,
    ): LimitRule {
        val candidate = requestedCandidate.copy(
            name = LimitRuleName.of(approvedRule.name),
            description = approvedRule.description,
            limitKind = approvedRule.toLimitKind(),
            amount = Money.positive(approvedRule.amount, CurrencyCode.of(approvedRule.currency)),
            mandateScope = approvedRule.toMandateScope(),
            configurationId = approvedRule.configurationId?.let { LimitConfigurationId.of(it) }
                ?: requestedCandidate.configurationId,
            channelScope = approvedRule.toChannelScope(),
            operationScope = approvedRule.toOperationScope(),
            usageConsumptionMode = LimitUsageConsumptionMode.valueOf(approvedRule.usageConsumptionMode.name),
            priority = LimitPriority.of(approvedRule.priority),
            effectiveWindow = EffectiveWindow(
                startsAt = approvedRule.startsAt ?: requestedCandidate.effectiveWindow.startsAt,
                endsAt = approvedRule.endsAt,
            ),
            metadata = Metadata(approvedRule.metadata),
            updatedAt = now,
        )
        if (candidate.isActive) {
            val applicationTime = effectiveAt ?: now
            require(candidate.effectiveWindow.endsAt == null || candidate.effectiveWindow.endsAt > applicationTime) {
                "active limit rule must not end before it is applied"
            }
        }
        return candidate
    }

    private fun validateCandidate(
        rule: LimitRule,
        facts: ResolvedLimitChangeFacts,
        changeType: LimitRuleChangeType,
    ): LimitRule {
        val configuration = facts.configuration
        if (configuration != null && changeType in CONFIGURATION_VALIDATED_CHANGES) {
            rule.validateAgainst(configuration, facts.ancestorConfigurations)
        }
        return rule
    }

    private fun fromDraft(
        command: RequestLimitRuleChangeCommand,
        draft: LimitRuleDraft,
        id: LimitRuleId,
        currentRule: LimitRule?,
        isActive: Boolean,
        version: Version,
        now: Instant,
    ): LimitRule =
        LimitRule(
            id = id,
            tenantId = currentRule?.tenantId ?: TenantId.of(command.tenantId),
            ownerPartyProfileId = currentRule?.ownerPartyProfileId
                ?: PartyProfileId.of(command.targetOwnerPartyProfileId),
            name = LimitRuleName.of(draft.name),
            description = draft.description,
            limitKind = draft.toLimitKind(),
            amount = Money.positive(draft.amount, CurrencyCode.of(draft.currency)),
            mandateScope = draft.toMandateScope(),
            configurationId = draft.configurationId?.let { LimitConfigurationId.of(it) }
                ?: currentRule?.configurationId,
            channelScope = draft.toChannelScope(),
            operationScope = draft.toOperationScope(),
            usageConsumptionMode = LimitUsageConsumptionMode.valueOf(draft.usageConsumptionMode.name),
            priority = LimitPriority.of(draft.priority),
            isActive = isActive,
            version = version,
            effectiveWindow = EffectiveWindow(
                startsAt = draft.startsAt ?: currentRule?.effectiveWindow?.startsAt ?: now,
                endsAt = draft.endsAt,
            ),
            metadata = Metadata(draft.metadata),
            createdAt = currentRule?.createdAt ?: now,
            updatedAt = now,
        )

    private companion object {
        val CONFIGURATION_VALIDATED_CHANGES = setOf(
            LimitRuleChangeType.CREATE,
            LimitRuleChangeType.UPDATE,
            LimitRuleChangeType.REACTIVATE,
        )
    }
}
