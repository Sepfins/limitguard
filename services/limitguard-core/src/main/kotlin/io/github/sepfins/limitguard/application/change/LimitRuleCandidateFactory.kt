package io.github.sepfins.limitguard.application.change

import io.github.sepfins.limitguard.api.commands.LimitRuleChangeOperation
import io.github.sepfins.limitguard.api.errors.ConflictException
import io.github.sepfins.limitguard.api.errors.NotFoundException
import io.github.sepfins.limitguard.application.toDomain
import io.github.sepfins.limitguard.domain.change.LimitRuleChangeRequest
import io.github.sepfins.limitguard.domain.change.LimitRuleChangeType
import io.github.sepfins.limitguard.domain.change.ResolvedLimitChangeFacts
import io.github.sepfins.limitguard.domain.common.LimitRuleId
import io.github.sepfins.limitguard.domain.common.Version
import io.github.sepfins.limitguard.domain.limit.LimitAmount
import io.github.sepfins.limitguard.domain.limit.LimitRule
import java.time.Instant

internal class LimitRuleCandidateFactory {
    fun changeType(operation: LimitRuleChangeOperation, facts: ResolvedLimitChangeFacts): LimitRuleChangeType =
        when (operation) {
            is LimitRuleChangeOperation.SetAmount ->
                if (facts.currentRule == null) LimitRuleChangeType.CREATE else LimitRuleChangeType.UPDATE
            LimitRuleChangeOperation.Deactivate -> {
                val rule = requireRule(facts.currentRule)
                if (!rule.isActive) throw ConflictException("limit rule is already inactive: ${rule.id.value}")
                LimitRuleChangeType.DEACTIVATE
            }
            LimitRuleChangeOperation.Reactivate -> {
                val rule = requireRule(facts.currentRule)
                if (rule.isActive) throw ConflictException("limit rule is already active: ${rule.id.value}")
                LimitRuleChangeType.REACTIVATE
            }
            LimitRuleChangeOperation.Delete -> {
                requireRule(facts.currentRule)
                LimitRuleChangeType.DELETE
            }
        }

    /** A status change keeps the rule's amount; it is still recorded as the requested amount. */
    fun requestedAmount(operation: LimitRuleChangeOperation, facts: ResolvedLimitChangeFacts): LimitAmount =
        when (operation) {
            is LimitRuleChangeOperation.SetAmount -> operation.amount.toDomain(facts.configuration.currency)
            else -> requireRule(facts.currentRule).amount
        }

    /**
     * The rule [request] produces from the target's rule row. Set amount changes only the
     * amount; a deleted row is granted again. A grant must fit the configuration, and every
     * envelope up to the root must be active at [at], both at submission and at application.
     */
    fun candidate(
        request: LimitRuleChangeRequest,
        target: LockedLimitChangeTarget,
        newRuleId: () -> LimitRuleId,
        at: Instant,
    ): LimitRule {
        val rule = target.ruleFor(request)
        val candidate = when (request.changeType) {
            LimitRuleChangeType.CREATE ->
                rule?.copy(amount = request.requestedAmount, isActive = true, version = rule.version.next(), updatedAt = at)
                    ?: LimitRule(
                        id = newRuleId(),
                        tenantId = request.tenantId,
                        ownerPartyProfileId = request.targetOwnerPartyProfileId,
                        configurationId = request.configurationId,
                        mandateScope = request.target.mandateScope,
                        amount = request.requestedAmount,
                        isActive = true,
                        version = Version.INITIAL,
                        createdAt = at,
                        updatedAt = at,
                    )
            LimitRuleChangeType.UPDATE -> requireRule(rule).let {
                it.copy(amount = request.requestedAmount, version = it.version.next(), updatedAt = at)
            }
            LimitRuleChangeType.DEACTIVATE, LimitRuleChangeType.DELETE -> requireRule(rule).let {
                it.copy(isActive = false, version = it.version.next(), updatedAt = at)
            }
            LimitRuleChangeType.REACTIVATE -> requireRule(rule).let {
                it.copy(isActive = true, version = it.version.next(), updatedAt = at)
            }
        }
        if (request.changeType in CONFIGURATION_VALIDATED_CHANGES) {
            val configuration = target.configuration
            require((listOf(configuration) + target.ancestors).all { it.isEffectiveAt(at) }) {
                "configuration must be active and effective up to its root: ${configuration.code.value}"
            }
            require(configuration.admits(candidate.amount, at)) {
                "rule amount must fit within configuration maxAssignableAmount; unlimited needs an unlimited maximum"
            }
        }
        return candidate
    }

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
