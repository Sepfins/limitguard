package io.github.sepfins.limitguard.application.change

import io.github.sepfins.limitguard.api.LimitRuleChangeType
import io.github.sepfins.limitguard.api.commands.RequestLimitRuleChangeCommand
import io.github.sepfins.limitguard.api.errors.ConflictException
import io.github.sepfins.limitguard.api.errors.NotFoundException
import io.github.sepfins.limitguard.application.configuration.resolveHierarchy
import io.github.sepfins.limitguard.domain.change.LimitRuleRevisionEventType
import io.github.sepfins.limitguard.domain.change.ResolvedLimitChangeFacts
import io.github.sepfins.limitguard.domain.common.LimitConfigurationId
import io.github.sepfins.limitguard.domain.common.LimitRuleId
import io.github.sepfins.limitguard.domain.common.MandateId
import io.github.sepfins.limitguard.domain.common.PartyProfileId
import io.github.sepfins.limitguard.domain.common.TenantId
import io.github.sepfins.limitguard.domain.limit.LimitRule
import io.github.sepfins.limitguard.domain.mandate.MandateScope
import io.github.sepfins.limitguard.port.persistence.LimitConfigurationRepository
import io.github.sepfins.limitguard.port.persistence.LimitRuleRepository
import io.github.sepfins.limitguard.port.persistence.LimitRuleRevisionRepository

internal class ChangeReferenceResolver(
    private val limitConfigurationRepository: LimitConfigurationRepository,
    private val limitRuleRepository: LimitRuleRepository,
    private val limitRuleRevisionRepository: LimitRuleRevisionRepository,
) {
    fun resolve(command: RequestLimitRuleChangeCommand): ResolvedLimitChangeFacts {
        val tenantId = TenantId.of(command.tenantId)
        val currentRule = command.limitRuleId?.let {
            limitRuleRepository.findByIdForUpdate(tenantId, LimitRuleId.of(it))
                ?: throw NotFoundException("limit rule not found: $it")
        }
        if (command.changeType != LimitRuleChangeType.CREATE) {
            requireNotNull(currentRule)
            if (currentRule.ownerPartyProfileId != PartyProfileId.of(command.targetOwnerPartyProfileId)) {
                throw ConflictException("target owner does not own limit rule: ${currentRule.id.value}")
            }
            if (currentRule.version.value != command.expectedVersion) {
                throw ConflictException(
                    "limit rule version conflict: expected ${command.expectedVersion}, found ${currentRule.version.value}"
                )
            }
            command.targetMandateId?.takeIf { command.requestedRule == null }?.let { targetMandateId ->
                if (currentRule.mandateId() != MandateId.of(targetMandateId)) {
                    throw ConflictException("target mandate does not match limit rule: ${currentRule.id.value}")
                }
            }
        }

        val latestRevision = currentRule
            ?.let { limitRuleRevisionRepository.findByLimitRule(tenantId, it.id) }
            ?.maxByOrNull { it.revisionNumber.value }
        if (latestRevision?.revisionEventType == LimitRuleRevisionEventType.DELETED) {
            throw ConflictException("limit rule has been deleted: ${currentRule.id.value}")
        }

        val configurationId = command.requestedRule?.configurationId
            ?.let { LimitConfigurationId.of(it) }
            ?: currentRule?.configurationId
        if (command.changeType == LimitRuleChangeType.CREATE && configurationId == null) {
            throw IllegalArgumentException("requestedRule.configurationId is required")
        }
        val configurationHierarchy = configurationId
            ?.let { limitConfigurationRepository.resolveHierarchy(tenantId, it) }
            .orEmpty()

        return ResolvedLimitChangeFacts(
            currentRule = currentRule,
            configuration = configurationHierarchy.firstOrNull(),
            ancestorConfigurations = configurationHierarchy.drop(1),
        )
    }

    private fun LimitRule.mandateId(): MandateId? =
        when (val scope = mandateScope) {
            MandateScope.AllOwnerMandates -> null
            is MandateScope.MandateSpecific -> scope.mandateId
        }
}
