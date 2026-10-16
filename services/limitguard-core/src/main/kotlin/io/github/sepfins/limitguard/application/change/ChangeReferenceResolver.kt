package io.github.sepfins.limitguard.application.change

import io.github.sepfins.limitguard.api.commands.RequestLimitRuleChangeCommand
import io.github.sepfins.limitguard.api.errors.ConflictException
import io.github.sepfins.limitguard.application.configuration.resolveHierarchy
import io.github.sepfins.limitguard.domain.change.LimitRuleRevisionEventType
import io.github.sepfins.limitguard.domain.change.ResolvedLimitChangeFacts
import io.github.sepfins.limitguard.domain.common.LimitConfigurationId
import io.github.sepfins.limitguard.domain.common.PartyProfileId
import io.github.sepfins.limitguard.domain.common.TenantId
import io.github.sepfins.limitguard.domain.limit.LimitRule
import io.github.sepfins.limitguard.port.persistence.LimitConfigurationRepository
import io.github.sepfins.limitguard.port.persistence.LimitRuleRepository
import io.github.sepfins.limitguard.port.persistence.LimitRuleRevisionRepository

internal class ChangeReferenceResolver(
    private val limitConfigurationRepository: LimitConfigurationRepository,
    private val limitRuleRepository: LimitRuleRepository,
    private val limitRuleRevisionRepository: LimitRuleRevisionRepository,
) {
    /** Call while holding the target lock: the rule found for the target is then locked for update. */
    fun resolve(command: RequestLimitRuleChangeCommand): ResolvedLimitChangeFacts {
        val tenantId = TenantId.of(command.tenantId)
        val hierarchy = limitConfigurationRepository.resolveHierarchy(
            tenantId, LimitConfigurationId.of(command.target.configurationId),
        )
        val currentRule = findRule(command)?.let {
            limitRuleRepository.findByIdForUpdate(tenantId, it.id)
        }
        if (command.expectedVersion != null && currentRule?.version?.value != command.expectedVersion) {
            throw ConflictException(
                "limit rule version conflict: expected ${command.expectedVersion}, found ${currentRule?.version?.value ?: "no rule"}"
            )
        }
        return ResolvedLimitChangeFacts(
            currentRule = currentRule,
            configuration = hierarchy.first(),
            ancestorConfigurations = hierarchy.drop(1),
        )
    }

    private fun findRule(command: RequestLimitRuleChangeCommand): LimitRule? {
        val tenantId = TenantId.of(command.tenantId)
        val configurationId = LimitConfigurationId.of(command.target.configurationId)
        val mandateScope = command.toMandateScope()
        val rules = limitRuleRepository.findByOwner(tenantId, PartyProfileId.of(command.target.ownerPartyProfileId))
            .filter { it.configurationId == configurationId && it.mandateScope == mandateScope && (it.isActive || !isDeleted(it)) }
        if (rules.size > 1) {
            throw ConflictException("more than one limit rule exists for the target: ${rules.map { it.id.value }}")
        }
        return rules.singleOrNull()
    }

    private fun isDeleted(rule: LimitRule): Boolean =
        limitRuleRevisionRepository.findByLimitRule(rule.tenantId, rule.id)
            .maxByOrNull { it.revisionNumber.value }
            ?.revisionEventType == LimitRuleRevisionEventType.DELETED
}
